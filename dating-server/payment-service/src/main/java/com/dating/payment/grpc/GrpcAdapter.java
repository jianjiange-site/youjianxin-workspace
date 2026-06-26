package com.dating.payment.grpc;

import io.grpc.stub.StreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将 gRPC StreamObserver 异步回调转换为同步阻塞调用，供 HTTP Controller 复用 gRPC Service Bean。
 */
public final class GrpcAdapter {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private GrpcAdapter() {}

    /** 调用 void method(Request, StreamObserver&lt;T&gt;) 并同步等待结果 */
    public static <T> T invoke(GrpcCall<T> call) {
        return invoke(call, DEFAULT_TIMEOUT_MS);
    }

    public static <T> T invoke(GrpcCall<T> call, long timeoutMs) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            call.execute(new StreamObserver<T>() {
                @Override
                public void onNext(T value) {
                    future.complete(value);
                }

                @Override
                public void onError(Throwable t) {
                    future.completeExceptionally(t);
                }

                @Override
                public void onCompleted() {
                    // no-op: onNext already completed the future
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("gRPC call interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("gRPC call failed: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("gRPC call timed out after " + timeoutMs + "ms", e);
        }
    }

    @FunctionalInterface
    public interface GrpcCall<T> {
        void execute(StreamObserver<T> observer);
    }
}
