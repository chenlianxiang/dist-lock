package com.distlock.core.exception;

/**
 * 锁存储底座不可用或执行失败。该异常不能被当作普通锁竞争超时处理。
 */
public class LockStorageException extends RuntimeException {

    private final String operation;
    private final String lockKey;

    public LockStorageException(String operation, String lockKey, Throwable cause) {
        super("Lock storage operation [" + operation + "] failed for [" + lockKey + "]", cause);
        this.operation = operation;
        this.lockKey = lockKey;
    }

    public String getOperation() {
        return operation;
    }

    public String getLockKey() {
        return lockKey;
    }
}
