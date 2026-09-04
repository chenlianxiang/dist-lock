package com.distlock.core.api;

import com.distlock.core.exception.LockTimeoutException;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一次锁竞争的类型化结果。仅将正常竞争超时转换为结果，基础设施异常不会被吞掉。
 */
public final class LockOutcome<R> {

    public enum Status {
        ACQUIRED,
        TIMEOUT
    }

    private final Status status;
    private final R value;
    private final List<String> lockKeys;
    private final long waitTimeoutMillis;

    private LockOutcome(Status status, R value, List<String> lockKeys, long waitTimeoutMillis) {
        this.status = status;
        this.value = value;
        this.lockKeys = List.copyOf(lockKeys);
        this.waitTimeoutMillis = waitTimeoutMillis;
    }

    public static <R> LockOutcome<R> acquired(R value) {
        return new LockOutcome<>(Status.ACQUIRED, value, List.of(), 0);
    }

    public static <R> LockOutcome<R> timeout(List<String> lockKeys, long waitTimeoutMillis) {
        return new LockOutcome<>(Status.TIMEOUT, null, List.copyOf(lockKeys), waitTimeoutMillis);
    }

    public Status status() {
        return status;
    }

    public boolean isAcquired() {
        return status == Status.ACQUIRED;
    }

    public R getOrThrow() {
        if (isAcquired()) {
            return value;
        }
        throw defaultTimeoutException();
    }

    public R orElse(R fallbackValue) {
        return isAcquired() ? value : fallbackValue;
    }

    public R orElseGet(Supplier<? extends R> fallback) {
        Objects.requireNonNull(fallback, "fallback must not be null");
        return isAcquired() ? value : fallback.get();
    }

    public <X extends RuntimeException> R orElseThrow(Supplier<? extends X> exceptionSupplier) {
        Objects.requireNonNull(exceptionSupplier, "exceptionSupplier must not be null");
        if (isAcquired()) {
            return value;
        }
        X exception = exceptionSupplier.get();
        if (exception == null) {
            throw defaultTimeoutException();
        }
        throw exception;
    }

    public List<String> lockKeys() {
        return List.copyOf(lockKeys);
    }

    public long waitTimeoutMillis() {
        return waitTimeoutMillis;
    }

    private LockTimeoutException defaultTimeoutException() {
        return new LockTimeoutException("lock" + lockKeys, waitTimeoutMillis);
    }
}
