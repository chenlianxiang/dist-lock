package com.distlock.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分布式锁组件配置属性。
 */
@ConfigurationProperties(prefix = "dist-lock")
public class DistributedLockProperties {

    /**
     * 存储底座类型（目前支持 database，后续可扩展 redis、zookeeper 等）。
     */
    private StorageType type = StorageType.DATABASE;

    /**
     * 默认最大等待锁超时时间（毫秒），默认为 3000ms。
     */
    private long defaultWaitTimeout = 3000;

    /**
     * 默认锁有效租约时长（毫秒），默认为 30000ms (30s)。
     */
    private long defaultLeaseTime = 30000;

    /**
     * 是否默认启用看门狗自动续约，默认为 true。
     */
    private boolean watchdogEnabled = true;

    public enum StorageType {
        DATABASE,
        REDIS,
        ZOOKEEPER
    }

    public StorageType getType() {
        return type;
    }

    public void setType(StorageType type) {
        this.type = type;
    }

    public long getDefaultWaitTimeout() {
        return defaultWaitTimeout;
    }

    public void setDefaultWaitTimeout(long defaultWaitTimeout) {
        this.defaultWaitTimeout = defaultWaitTimeout;
    }

    public long getDefaultLeaseTime() {
        return defaultLeaseTime;
    }

    public void setDefaultLeaseTime(long defaultLeaseTime) {
        this.defaultLeaseTime = defaultLeaseTime;
    }

    public boolean isWatchdogEnabled() {
        return watchdogEnabled;
    }

    public void setWatchdogEnabled(boolean watchdogEnabled) {
        this.watchdogEnabled = watchdogEnabled;
    }
}
