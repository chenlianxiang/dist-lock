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

    /** 看门狗调度线程数。 */
    private int watchdogThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    /** 单次数据库锁事务与 SQL 超时（毫秒）。 */
    private long databaseOperationTimeout = 3000;

    /** fencing 语义。REQUIRED 只装配具备同组件原子 Guard 的策略。 */
    private FencingMode fencingMode = FencingMode.REQUIRED;

    /** fencing 事务（包含业务闭包）的超时时间（毫秒）。 */
    private long fencingTransactionTimeout = 30000;

    public enum StorageType {
        DATABASE,
        REDIS,
        ZOOKEEPER
    }

    public enum FencingMode {
        REQUIRED,
        DISABLED
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

    public int getWatchdogThreads() {
        return watchdogThreads;
    }

    public void setWatchdogThreads(int watchdogThreads) {
        if (watchdogThreads <= 0) {
            throw new IllegalArgumentException("watchdogThreads must be greater than 0");
        }
        this.watchdogThreads = watchdogThreads;
    }

    public long getDatabaseOperationTimeout() {
        return databaseOperationTimeout;
    }

    public void setDatabaseOperationTimeout(long databaseOperationTimeout) {
        if (databaseOperationTimeout <= 0) {
            throw new IllegalArgumentException("databaseOperationTimeout must be greater than 0");
        }
        this.databaseOperationTimeout = databaseOperationTimeout;
    }

    public FencingMode getFencingMode() {
        return fencingMode;
    }

    public void setFencingMode(FencingMode fencingMode) {
        if (fencingMode == null) {
            throw new IllegalArgumentException("fencingMode must not be null");
        }
        this.fencingMode = fencingMode;
    }

    public long getFencingTransactionTimeout() {
        return fencingTransactionTimeout;
    }

    public void setFencingTransactionTimeout(long fencingTransactionTimeout) {
        if (fencingTransactionTimeout <= 0) {
            throw new IllegalArgumentException("fencingTransactionTimeout must be greater than 0");
        }
        this.fencingTransactionTimeout = fencingTransactionTimeout;
    }
}
