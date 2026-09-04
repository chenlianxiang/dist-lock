package com.distlock.core.spi;

/**
 * 存储层单次非阻塞获取结果。fencingToken 仅在 acquired=true 时有效。
 */
public record LockAcquisition(boolean acquired, long fencingToken) {

    public LockAcquisition {
        if (acquired && fencingToken <= 0) {
            throw new IllegalArgumentException("acquired lock must have a positive fencing token");
        }
        if (!acquired && fencingToken != 0) {
            throw new IllegalArgumentException("contended lock must have fencing token 0");
        }
    }

    public static LockAcquisition acquired(long fencingToken) {
        return new LockAcquisition(true, fencingToken);
    }

    public static LockAcquisition contended() {
        return new LockAcquisition(false, 0);
    }
}
