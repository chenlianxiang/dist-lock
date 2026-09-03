package com.distlock.core.exception;

/**
 * 分布式锁等待超时或争抢失败异常。
 * <p>
 * 默认提供固定稳健的系统兜底提示，同时支持用户按需传入特制的友好错误信息。
 */
public class LockTimeoutException extends LockAcquisitionException {

    public static final String DEFAULT_MESSAGE = "系统繁忙，当前业务正在处理中，请稍候重试";

    private final long waitTimeoutMillis;
    private final String friendlyMessage;

    public LockTimeoutException(String lockKey, long waitTimeoutMillis) {
        this(lockKey, waitTimeoutMillis, DEFAULT_MESSAGE);
    }

    public LockTimeoutException(String lockKey, long waitTimeoutMillis, String friendlyMessage) {
        super(lockKey, (friendlyMessage != null && !friendlyMessage.isBlank()) ? friendlyMessage : DEFAULT_MESSAGE);
        this.waitTimeoutMillis = waitTimeoutMillis;
        this.friendlyMessage = (friendlyMessage != null && !friendlyMessage.isBlank()) ? friendlyMessage : DEFAULT_MESSAGE;
    }

    public long getWaitTimeoutMillis() {
        return waitTimeoutMillis;
    }

    public String getFriendlyMessage() {
        return friendlyMessage;
    }
}
