package com.distlock.core.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 在链式配置执行时，根据 strategy 选择具体锁执行器。
 */
public class RoutingDistributedLocker implements DistributedLocker {

    private final Map<String, DistributedLocker> lockers = new ConcurrentHashMap<>();
    private final String defaultStrategyName;

    public RoutingDistributedLocker(Map<String, DistributedLocker> initialLockers, String defaultStrategyName) {
        if (initialLockers != null) {
            initialLockers.forEach((key, value) ->
                    lockers.put(normalize(key), Objects.requireNonNull(value, "locker must not be null")));
        }
        this.defaultStrategyName = defaultStrategyName != null && !defaultStrategyName.isBlank()
                ? normalize(defaultStrategyName)
                : LockStrategy.DATABASE.name();
    }

    public RoutingDistributedLocker register(LockStrategy strategy, DistributedLocker locker) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        lockers.put(normalize(strategy.name()), Objects.requireNonNull(locker, "locker must not be null"));
        return this;
    }

    @Override
    public LockOperation lock(String namespace, Object key) {
        return LockOperation.single(namespace, key, this::execute);
    }

    @Override
    public <T> LockOperation locks(String namespace,
                                   Collection<T> resources,
                                   Function<T, ?> keyExtractor) {
        return LockOperation.batch(namespace, resources, keyExtractor, this::execute);
    }

    private LockOutcome<?> execute(LockOperation.Snapshot snapshot, Supplier<?> action) {
        String strategyName = snapshot.strategy() == null
                ? defaultStrategyName : normalize(snapshot.strategy().name());
        DistributedLocker target = requireLocker(strategyName);

        LockOperation operation = snapshot.businessKeys().size() == 1
                ? target.lock(snapshot.namespace(), snapshot.businessKeys().get(0))
                : target.locks(snapshot.namespace(), snapshot.businessKeys(), Function.identity());

        if (snapshot.strategy() != null) {
            operation = operation.strategy(snapshot.strategy());
        }
        if (snapshot.waitTimeoutMillis() != null) {
            operation = operation.waitTimeout(Duration.ofMillis(snapshot.waitTimeoutMillis()));
        }
        if (snapshot.leaseMillis() != null) {
            operation = operation.leaseTime(Duration.ofMillis(snapshot.leaseMillis()));
        }
        if (snapshot.watchdogEnabled() != null) {
            operation = operation.watchdog(snapshot.watchdogEnabled());
        }
        return operation.tryCall(action);
    }

    private DistributedLocker requireLocker(String strategyName) {
        DistributedLocker locker = lockers.get(strategyName);
        if (locker == null) {
            throw new IllegalStateException("Lock strategy [" + strategyName
                    + "] is not available. Registered strategies: " + lockers.keySet());
        }
        return locker;
    }

    private static String normalize(String strategyName) {
        return strategyName.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
