package com.distlock.core.exception;

/**
 * 业务执行期间租约已失效或续期失败到无法保证所有权。
 */
public class LockLostException extends LockAcquisitionException {

    public LockLostException(String lockKey, String message) {
        super(lockKey, message);
    }

    public LockLostException(String lockKey, String message, Throwable cause) {
        super(lockKey, message, cause);
    }
}
