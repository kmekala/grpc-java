/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.internal;

import com.google.common.base.Strings;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.PickFirstLoadBalancer.PickFirstLoadBalancerConfig;
import java.util.Map;

/**
 * Provider for the "pick_first" balancing policy.
 *
 * <p>This provides no load-balancing over the addresses from the {@link NameResolver}.  It walks
 * down the address list and sticks to the first that works.
 */
public final class PickFirstLoadBalancerProvider extends LoadBalancerProvider {
  private static final String SHUFFLE_ADDRESS_LIST_KEY = "shuffleAddressList";

  static boolean enableNewPickFirst =
      !Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_ENABLE_NEW_PICK_FIRST"))
          && Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_ENABLE_NEW_PICK_FIRST"));

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return "pick_first";
  }

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    if (enableNewPickFirst) {
      return new PickFirstLeafLoadBalancer(helper);
    } else {
      return new PickFirstLoadBalancer(helper);
    }
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    try {
      return ConfigOrError.fromConfig(
          new PickFirstLoadBalancerConfig(JsonUtil.getBoolean(rawLoadBalancingPolicyConfig,
              SHUFFLE_ADDRESS_LIST_KEY)));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNAVAILABLE.withCause(e).withDescription(
              "Failed parsing configuration for " + getPolicyName()));
    }
  }
}
