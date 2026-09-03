package com.distlock.core.exception;

/**
 * 分布式锁异常基类。
 */
public class LockAcquisitionException extends RuntimeException {

    private final String lockKey;

    public LockAcquisitionException(String lockKey, String message) {
        super(message);
        this.lockKey = lockKey;
    }

    public LockAcquisitionException(String lockKey, String message, Throwable cause) {
        super(message, cause);
        this.lockKey = lockKey;
    }

    public String getLockKey() {
        return lockKey;
    }
}
