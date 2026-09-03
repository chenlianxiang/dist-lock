package com.distlock.core.api;

/**
 * 一项已获取锁及其单调递增的 fencing token。
 */
public record LockLease(String lockKey, long fencingToken) {

    public LockLease {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("lockKey must not be blank");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be greater than 0");
        }
    }
}
