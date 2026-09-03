package com.distlock.core.api;

/**
 * 分布式锁存储引擎策略接口。
 * <p>
 * 符合开闭原则（OCP），内置常用底座，同时允许任意第三方或业务模块自主扩展。
 */
@FunctionalInterface
public interface LockStrategy {

    /**
     * 获取策略唯一标识名称。
     */
    String name();

    /**
     * 关系型数据库 CAS 租约锁策略（默认大部分常规业务推荐，零外部中间件依赖）。
     */
    LockStrategy DATABASE = () -> "DATABASE";

    /**
     * 内存缓存极速锁策略（适合高频、秒杀、大流量并发场景）。
     */
    LockStrategy REDIS = () -> "REDIS";

    /**
     * 强一致性协调服务锁策略。
     */
    LockStrategy ZOOKEEPER = () -> "ZOOKEEPER";

    /**
     * 允许业务方自主声明并扩展自定义存储策略（如 ETCD、CONSUL 等）。
     *
     * @param name 策略名称
     * @return 锁策略实例
     */
    static LockStrategy of(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Lock strategy name must not be blank");
        }
        String upperName = name.trim().toUpperCase();
        return () -> upperName;
    }
}
