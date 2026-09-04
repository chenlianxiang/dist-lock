package com.distlock.core.exception;

/**
 * fencing token 已被更新的持有者超越，当前业务闭包不得执行。
 */
public final class FencingRejectedException extends LockAcquisitionException {

    private final long rejectedToken;

    public FencingRejectedException(String lockKey, long rejectedToken) {
        super(lockKey, "Fencing token [" + rejectedToken + "] is stale for lock [" + lockKey + "]");
        this.rejectedToken = rejectedToken;
    }

    public long rejectedToken() {
        return rejectedToken;
    }
}
