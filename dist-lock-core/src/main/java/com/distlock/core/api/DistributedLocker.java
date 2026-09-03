package com.distlock.core.api;

import java.util.function.Function;

/**
 * 分布式锁执行门面。
 * <p>
 * 唯一入口同时接受单个对象或对象集合，并返回可链式配置的 {@link LockOperation}。
 * 单对象在内部统一包装为集合处理。
 * 业务执行由操作对象末端的 call/run/tryCall 触发。
 */
public interface DistributedLocker {

    <T> LockOperation lock(Object resourceOrResources,
                           Function<T, ?> keyExtractor);
}
