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
        this.waitTimeoutMillis = waitTimeoutMillis;
        this.leaseMillis = leaseMillis;
        this.watchdogEnabled = watchdogEnabled;
    }

    public static LockConfig defaultConfig() {
        return new LockConfig(3000, 30000, true);
    }

    public static LockConfig of(long waitTimeout, TimeUnit waitUnit, long leaseTime, TimeUnit leaseUnit, boolean watchdog) {
        return new LockConfig(waitUnit.toMillis(waitTimeout), leaseUnit.toMillis(leaseTime), watchdog);
    }

    public LockConfig withTimeout(long waitTimeout, TimeUnit unit) {
        return new LockConfig(unit.toMillis(waitTimeout), this.leaseMillis, this.watchdogEnabled);
    }

    public LockConfig withLease(long leaseTime, TimeUnit unit) {
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
