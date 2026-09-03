package com.distlock.core.api;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁运行时配置项。
 */
public class LockConfig {

    private final long waitTimeoutMillis;
    private final long leaseMillis;
    private final boolean watchdogEnabled;

    public LockConfig(long waitTimeoutMillis, long leaseMillis, boolean watchdogEnabled) {
        if (waitTimeoutMillis < 0) {
            throw new IllegalArgumentException("waitTimeoutMillis must be greater than or equal to 0");
        }
        if (leaseMillis <= 0) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.waitTimeoutMillis = waitTimeoutMillis;
        this.leaseMillis = leaseMillis;
        this.watchdogEnabled = watchdogEnabled;
    }

    public static LockConfig defaultConfig() {
        return new LockConfig(3000, 30000, true);
    }

    public static LockConfig of(long waitTimeout, TimeUnit waitUnit, long leaseTime, TimeUnit leaseUnit, boolean watchdog) {
        if (waitUnit == null || leaseUnit == null) {
            throw new IllegalArgumentException("time units must not be null");
        }
        return new LockConfig(waitUnit.toMillis(waitTimeout), leaseUnit.toMillis(leaseTime), watchdog);
    }

    public LockConfig withTimeout(long waitTimeout, TimeUnit unit) {
        if (unit == null) {
            throw new IllegalArgumentException("time unit must not be null");
        }
        return new LockConfig(unit.toMillis(waitTimeout), this.leaseMillis, this.watchdogEnabled);
    }

    public LockConfig withLease(long leaseTime, TimeUnit unit) {
        if (unit == null) {
            throw new IllegalArgumentException("time unit must not be null");
        }
        return new LockConfig(this.waitTimeoutMillis, unit.toMillis(leaseTime), this.watchdogEnabled);
    }

    public LockConfig withWatchdog(boolean enabled) {
        return new LockConfig(this.waitTimeoutMillis, this.leaseMillis, enabled);
    }

    public long getWaitTimeoutMillis() {
        return waitTimeoutMillis;
    }

    public long getLeaseMillis() {
        return leaseMillis;
    }

    public boolean isWatchdogEnabled() {
        return watchdogEnabled;
    }
}
