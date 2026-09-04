package com.distlock.core.api;

import java.util.List;

/**
 * 一次锁获取的只读凭证。关键写入应把 fencing token 一并提交给下游存储校验。
 */
public record LockHandle(String owner, List<LockLease> leases) {

    public LockHandle {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        leases = List.copyOf(leases);
        if (leases.isEmpty()) {
            throw new IllegalArgumentException("leases must not be empty");
        }
    }

    public long fencingToken() {
        if (leases.size() != 1) {
            throw new IllegalStateException("fencingToken() requires exactly one lock; use fencingToken(lockKey)");
        }
        return leases.get(0).fencingToken();
    }

    public long fencingToken(String lockKey) {
        return leases.stream()
                .filter(lease -> lease.lockKey().equals(lockKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No acquired lock for key [" + lockKey + "]"))
                .fencingToken();
    }
}
