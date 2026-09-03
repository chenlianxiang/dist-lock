package com.distlock.core.api;

import java.util.Collection;
import java.util.function.Function;

/**
 * 分布式锁执行门面。
 * <p>
 * 入口只负责声明单个或批量锁资源，并返回可链式配置的 {@link LockOperation}。
 * 业务执行由操作对象末端的 call/run/tryCall 触发。
 */
public interface DistributedLocker {

    LockOperation lock(String namespace, Object key);

    <T> LockOperation locks(String namespace, Collection<T> resources, Function<T, ?> keyExtractor);
}
