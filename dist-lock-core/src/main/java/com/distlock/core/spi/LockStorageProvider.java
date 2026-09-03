package com.distlock.core.spi;

/**
 * 分布式锁存储底层原子原语 SPI 接口。
 * <p>
 * 所有底层存储引擎（Database、Redis、ZooKeeper、Etcd 等）均通过实现本契约接入。
 * 核心层依托此原子接口完成生命周期治理、看门狗自动续期及自适应等待重试。
 */
public interface LockStorageProvider {

    /**
     * 尝试原子获取锁 / 建立租约（单次非阻塞原子操作）。
     *
     * @param lockKey     锁资源标识
     * @param owner       持有者全局唯一标识（通常为 NodeId:PID:ThreadId）
     * @param leaseMillis 期望租约有效时长（毫秒）
     * @return true 获取成功；false 锁已被其他有效租约占有
     */
    boolean tryAcquire(String lockKey, String owner, long leaseMillis);

    /**
     * 原子释放锁。
     * <p>
     * 严格遵守“解铃还须系铃人”原则，必须保证仅持有者本人能完成释放，杜绝误删。
     *
     * @param lockKey 锁资源标识
     * @param owner   持有者全局唯一标识
     * @return true 释放成功；false 锁不存在或已被其他节点接管
     */
    boolean release(String lockKey, String owner);

    /**
     * 原子续期租约（主要供 Watchdog 调度器使用）。
     *
     * @param lockKey     锁资源标识
     * @param owner       持有者全局唯一标识
     * @param leaseMillis 续约延长的有效时长（毫秒）
     * @return true 续约成功；false 锁已失效或已被他人接管
     */
    boolean renew(String lockKey, String owner, long leaseMillis);

    /**
     * 获取存储端当前全局基准时钟（毫秒时间戳）。
     * <p>
     * 在跨多节点、多机房部署时，不同服务器由于 NTP 偏差或时钟漂移（Clock Skew），
     * 必须以统一存储端（如 DB / Redis 时钟）为准，避免本地时间不同步导致锁过早失效或死锁。
     *
     * @return 存储端当前系统时间戳（毫秒）
     */
    long getStorageTimeMillis();
}
