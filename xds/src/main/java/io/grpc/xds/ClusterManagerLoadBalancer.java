/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The top-level load balancing policy for use in XDS.
 * This policy does not immediately delete its children.  Instead, it marks them deactivated
 * and starts a timer for deletion.  If a subsequent address update restores the child, then it is
 * simply reactivated instead of built from scratch.  This is necessary because XDS can frequently
 * remove and then add back a server as machines are rebooted or repurposed for load management.
 *
 * <p>Note that this LB does not automatically reconnect children who go into IDLE status
 */
class ClusterManagerLoadBalancer extends MultiChildLoadBalancer {

  // 15 minutes is long enough for a reboot and the services to restart while not so long that
  // many children are waiting for cleanup.
  @VisibleForTesting
  public static final int DELAYED_CHILD_DELETION_TIME_MINUTES = 15;
  protected final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final XdsLogger logger;

  ClusterManagerLoadBalancer(Helper helper) {
    super(helper);
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster_manager-lb", helper.getAuthority()));

    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  protected ResolvedAddresses getChildAddresses(Object key, ResolvedAddresses resolvedAddresses,
      Object childConfig) {
    return resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(childConfig).build();
  }

  @Override
  protected Map<Object, ChildLbState> createChildLbMap(ResolvedAddresses resolvedAddresses) {
    ClusterManagerConfig config = (ClusterManagerConfig)
        resolvedAddresses.getLoadBalancingPolicyConfig();
    Map<Object, ChildLbState> newChildPolicies = new HashMap<>();
    if (config != null) {
      for (Entry<String, PolicySelection> entry : config.childPolicies.entrySet()) {
        ChildLbState child = getChildLbState(entry.getKey());
        if (child == null) {
          child = new ClusterManagerLbState(entry.getKey(),
              entry.getValue().getProvider(), entry.getValue().getConfig(), getInitialPicker());
        }
        newChildPolicies.put(entry.getKey(), child);
      }
    }
    logger.log(
        XdsLogLevel.INFO,
        "Received cluster_manager lb config: child names={0}", newChildPolicies.keySet());
    return newChildPolicies;
  }

  /**
   * This is like the parent except that it doesn't shutdown the removed children since we want that
   * to be done by the timer.
   */
  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    try {
      resolvingAddresses = true;

      // process resolvedAddresses to update children
      AcceptResolvedAddressRetVal acceptRetVal =
          acceptResolvedAddressesInternal(resolvedAddresses);
      if (!acceptRetVal.status.isOk()) {
        return acceptRetVal.status;
      }

      // Update the picker
      updateOverallBalancingState();

      return acceptRetVal.status;
    } finally {
      resolvingAddresses = false;
    }
  }

  @Override
  protected SubchannelPicker getSubchannelPicker(Map<Object, SubchannelPicker> childPickers) {
    return new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        String clusterName =
            args.getCallOptions().getOption(XdsNameResolver.CLUSTER_SELECTION_KEY);
        SubchannelPicker childPicker = childPickers.get(clusterName);
        if (childPicker == null) {
          return
              PickResult.withError(
                  Status.UNAVAILABLE.withDescription("CDS encountered error: unable to find "
                      + "available subchannel for cluster " + clusterName));
        }
        return childPicker.pickSubchannel(args);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("pickers", childPickers).toString();
      }
    };
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    for (ChildLbState state : getChildLbStates()) {
      if (!state.isDeactivated()) {
        gotoTransientFailure = false;
        handleNameResolutionError(state, error);
      }
    }
    if (gotoTransientFailure) {
      getHelper().updateBalancingState(TRANSIENT_FAILURE, getErrorPicker(error));
    }
  }

  @Override
  protected boolean reconnectOnIdle() {
    return false;
  }

  /**
   * This differs from the base class in the use of the deletion timer.  When it is deactivated,
   * rather than immediately calling shutdown it starts a timer.  If shutdown or reactivate
   * are called before the timer fires, the timer is canceled.  Otherwise, time timer calls shutdown
   * and removes the child from the petiole policy when it is triggered.
   */
  private class ClusterManagerLbState extends ChildLbState {
    @Nullable
    ScheduledHandle deletionTimer;

    public ClusterManagerLbState(Object key, LoadBalancerProvider policyProvider,
        Object childConfig, SubchannelPicker initialPicker) {
      super(key, policyProvider, childConfig, initialPicker);
    }

    @Override
    protected void shutdown() {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
      }
      super.shutdown();
    }

    @Override
    protected void reactivate(LoadBalancerProvider policyProvider) {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
        logger.log(XdsLogLevel.DEBUG, "Child balancer {0} reactivated", getKey());
      }

      super.reactivate(policyProvider);
    }

    @Override
    protected void deactivate() {
      if (isDeactivated()) {
        return;
      }

      class DeletionTask implements Runnable {

        @Override
        public void run() {
          shutdown();
          removeChild(getKey());
        }
      }

      deletionTimer =
          syncContext.schedule(
              new DeletionTask(),
              DELAYED_CHILD_DELETION_TIME_MINUTES,
              TimeUnit.MINUTES,
              timeService);
      setDeactivated();
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} deactivated", getKey());
    }

  }
}
