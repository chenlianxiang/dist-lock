package com.distlock.core.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 分布式锁多引擎动态路由器。
 * <p>
 * 支持同时注册 DB、Redis、Zookeeper 等多种底层锁实现，通过 {@link #use(LockStrategy)} 自主动态分发。
 * 未显式声明策略时，自动路由到默认底座（如大部分常规业务默认走 DATABASE）。
 */
public class RoutingDistributedLocker implements DistributedLocker {

    private final Map<String, DistributedLocker> lockers = new ConcurrentHashMap<>();
    private final String defaultStrategyName;

    public RoutingDistributedLocker(Map<String, DistributedLocker> initialLockers, String defaultStrategyName) {
        if (initialLockers != null) {
            initialLockers.forEach((k, v) -> this.lockers.put(k.toUpperCase(), v));
        }
        this.defaultStrategyName = (defaultStrategyName != null && !defaultStrategyName.isBlank())
                ? defaultStrategyName.toUpperCase()
                : LockStrategy.DATABASE.name();
    }

    /**
     * 注册新的锁策略引擎。
     */
    public RoutingDistributedLocker register(LockStrategy strategy, DistributedLocker locker) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(locker, "locker must not be null");
        this.lockers.put(strategy.name().toUpperCase(), locker);
        return this;
    }

    @Override
    public DistributedLocker use(LockStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        String name = strategy.name().toUpperCase();
        DistributedLocker target = lockers.get(name);
        if (target == null) {
            throw new IllegalArgumentException("Unsupported lock strategy [" + name + "], currently available strategies: " + lockers.keySet());
        }
        return target;
    }

    private DistributedLocker getDefaultLocker() {
        DistributedLocker defaultLocker = lockers.get(defaultStrategyName);
        if (defaultLocker == null) {
            throw new IllegalStateException("Default lock strategy [" + defaultStrategyName
                    + "] is not available. Registered strategies: " + lockers.keySet());
        }
        return defaultLocker;
    }

    // =========================================================================
    // 默认全量委托到 defaultLocker
    // =========================================================================

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, Function<T, R> action) {
        return getDefaultLocker().lock(data, keyExtractor, action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, Consumer<T> action) {
        getDefaultLocker().lock(data, keyExtractor, action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, String errorMessage, Function<T, R> action) {
        return getDefaultLocker().lock(data, keyExtractor, errorMessage, action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, String errorMessage, Consumer<T> action) {
        getDefaultLocker().lock(data, keyExtractor, errorMessage, action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Function<T, R> action) {
        return getDefaultLocker().lock(data, keyExtractor, exceptionSupplier, action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<T> action) {
        getDefaultLocker().lock(data, keyExtractor, exceptionSupplier, action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, Function<T, R> action, Function<T, R> fallback) {
        return getDefaultLocker().lock(data, keyExtractor, action, fallback);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, Consumer<T> action, Consumer<T> fallback) {
        getDefaultLocker().lock(data, keyExtractor, action, fallback);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Function<T, R> action) {
        return getDefaultLocker().lock(data, keyExtractor, timeout, unit, errorMessage, action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Consumer<T> action) {
        getDefaultLocker().lock(data, keyExtractor, timeout, unit, errorMessage, action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Function<T, R> action) {
        return getDefaultLocker().lock(data, keyExtractor, timeout, unit, exceptionSupplier, action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<T> action) {
        getDefaultLocker().lock(data, keyExtractor, timeout, unit, exceptionSupplier, action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Function<T, R> action, Function<T, R> fallback) {
        return getDefaultLocker().lock(data, keyExtractor, timeout, unit, action, fallback);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Consumer<T> action, Consumer<T> fallback) {
        getDefaultLocker().lock(data, keyExtractor, timeout, unit, action, fallback);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Function<C, R> action) {
        return getDefaultLocker().lock(dataList, keyExtractor, action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Consumer<C> action) {
        getDefaultLocker().lock(dataList, keyExtractor, action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, String errorMessage, Function<C, R> action) {
        return getDefaultLocker().lock(dataList, keyExtractor, errorMessage, action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, String errorMessage, Consumer<C> action) {
        getDefaultLocker().lock(dataList, keyExtractor, errorMessage, action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Function<C, R> action) {
        return getDefaultLocker().lock(dataList, keyExtractor, exceptionSupplier, action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<C> action) {
        getDefaultLocker().lock(dataList, keyExtractor, exceptionSupplier, action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Function<C, R> action, Function<C, R> fallback) {
        return getDefaultLocker().lock(dataList, keyExtractor, action, fallback);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Consumer<C> action, Consumer<C> fallback) {
        getDefaultLocker().lock(dataList, keyExtractor, action, fallback);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Function<C, R> action) {
        return getDefaultLocker().lock(dataList, keyExtractor, timeout, unit, errorMessage, action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Consumer<C> action) {
        getDefaultLocker().lock(dataList, keyExtractor, timeout, unit, errorMessage, action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Function<C, R> action) {
        return getDefaultLocker().lock(dataList, keyExtractor, timeout, unit, exceptionSupplier, action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<C> action) {
        getDefaultLocker().lock(dataList, keyExtractor, timeout, unit, exceptionSupplier, action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Function<C, R> action, Function<C, R> fallback) {
        return getDefaultLocker().lock(dataList, keyExtractor, timeout, unit, action, fallback);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Consumer<C> action, Consumer<C> fallback) {
        getDefaultLocker().lock(dataList, keyExtractor, timeout, unit, action, fallback);
    }
}
