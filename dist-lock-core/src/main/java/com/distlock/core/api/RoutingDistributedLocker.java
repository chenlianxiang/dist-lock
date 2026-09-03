package com.distlock.core.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
    public <T> LockOperation lock(Object resourceOrResources,
                                  Function<T, ?> keyExtractor) {
        return LockOperation.create(resourceOrResources, keyExtractor, this::execute);
    }

    private LockOutcome<?> execute(LockOperation.Snapshot snapshot, Function<LockHandle, ?> action) {
        String strategyName = snapshot.strategy() == null
                ? defaultStrategyName : normalize(snapshot.strategy().name());
        DistributedLocker target = requireLocker(strategyName);

        LockOperation operation = forward(target, snapshot);

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
        return operation.tryCallWithHandle(action);
    }

    private static LockOperation forward(DistributedLocker target, LockOperation.Snapshot snapshot) {
        List<RoutedResource> resources = snapshot.businessKeys().stream()
                .map(key -> new RoutedResource(snapshot.namespace(), key))
                .toList();
        return target.lock(resources, RoutedResource::key);
    }

    private record RoutedResource(Class<?> lockNamespace, Object key)
            implements LockOperation.NamespaceCarrier {
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
