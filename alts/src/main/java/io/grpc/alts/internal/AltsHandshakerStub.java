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

package io.grpc.alts.internal;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import io.grpc.alts.internal.HandshakerServiceGrpc.HandshakerServiceStub;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** An interface to the ALTS handshaker service. */
class AltsHandshakerStub {
  private final StreamObserver<HandshakerResp> reader = new Reader();
  private StreamObserver<HandshakerReq> writer;
  private final HandshakerServiceStub serviceStub;
  private final ArrayBlockingQueue<Optional<HandshakerResp>> responseQueue =
      new ArrayBlockingQueue<>(1);
  private final AtomicReference<ThrowableInfo> exceptionMessage = new AtomicReference<>();

  private static final long HANDSHAKE_RPC_DEADLINE_SECS = 20;

  AltsHandshakerStub(HandshakerServiceStub serviceStub) {
    this.serviceStub = serviceStub;
  }

  @VisibleForTesting
  AltsHandshakerStub() {
    serviceStub = null;
  }

  @VisibleForTesting
  AltsHandshakerStub(StreamObserver<HandshakerReq> writer) {
    this.writer = writer;
    serviceStub = null;
  }

  @VisibleForTesting
  StreamObserver<HandshakerResp> getReaderForTest() {
    return reader;
  }

  /** Send a handshaker request and return the handshaker response. */
  public HandshakerResp send(HandshakerReq req) throws InterruptedException, IOException {
    createWriterIfNull();
    maybeThrowIoException();
    if (!responseQueue.isEmpty()) {
      throw new IOException("Received an unexpected response.");
    }

    writer.onNext(req);
    Optional<HandshakerResp> result = responseQueue.take();
    if (result.isPresent()) {
      return result.get();
    }

    if (exceptionMessage.get() != null) {
      throw new IOException(exceptionMessage.get().info, exceptionMessage.get().throwable);
    } else {
      throw new IOException("No handshaker response received");
    }
  }

  /** Create a new writer if the writer is null. */
  private void createWriterIfNull() {
    if (writer == null) {
      writer =
          serviceStub.withDeadlineAfter(HANDSHAKE_RPC_DEADLINE_SECS, SECONDS).doHandshake(reader);
    }
  }

  /** Throw exception if there is an outstanding exception. */
  private void maybeThrowIoException() throws IOException {
    if (exceptionMessage.get() != null) {
      throw new IOException(exceptionMessage.get().info, exceptionMessage.get().throwable);
    }
  }

  /** Close the connection. */
  public void close() {
    if (writer != null) {
      writer.onCompleted();
    }
  }

  private class Reader implements StreamObserver<HandshakerResp> {
    /** Receive a handshaker response from the server. */
    @Override
    public void onNext(HandshakerResp resp) {
      try {
        AltsHandshakerStub.this.responseQueue.add(Optional.of(resp));
      } catch (IllegalStateException e) {
        AltsHandshakerStub.this.exceptionMessage.compareAndSet(
            null, new ThrowableInfo(e, "Received an unexpected response."));
        AltsHandshakerStub.this.close();
      }
    }

    /** Receive an error from the server. */
    @Override
    public void onError(Throwable t) {
      AltsHandshakerStub.this.exceptionMessage.compareAndSet(
          null, new ThrowableInfo(t, "Received a terminating error."));
      // Trigger the release of any blocked send.
      Optional<HandshakerResp> result = Optional.absent();
      AltsHandshakerStub.this.responseQueue.offer(result);
    }

    /** Receive the closing message from the server. */
    @Override
    public void onCompleted() {
      AltsHandshakerStub.this.exceptionMessage.compareAndSet(
          null, new ThrowableInfo(null, "Response stream closed."));
      // Trigger the release of any blocked send.
      Optional<HandshakerResp> result = Optional.absent();
      AltsHandshakerStub.this.responseQueue.offer(result);
    }
  }

  private static class ThrowableInfo {

    private final Throwable throwable;
    private final String info;

    private ThrowableInfo(Throwable throwable, String info) {
      this.throwable = throwable;
      this.info = info;
    }
  }
}
