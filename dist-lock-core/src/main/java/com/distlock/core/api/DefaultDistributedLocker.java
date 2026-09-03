package com.distlock.core.api;

import com.distlock.core.backoff.AdaptiveBackoff;
import com.distlock.core.context.LockOwner;
import com.distlock.core.exception.LockAcquisitionException;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.watchdog.WatchdogCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 绑定单一存储策略的默认锁执行器。
 */
public class DefaultDistributedLocker implements DistributedLocker {

    private static final Logger log = LoggerFactory.getLogger(DefaultDistributedLocker.class);
    private static final ThreadLocal<Set<String>> HELD_LOCK_KEYS = ThreadLocal.withInitial(HashSet::new);

    private final LockStorageProvider storageProvider;
    private final WatchdogCoordinator watchdogCoordinator;
    private final LockConfig defaultConfig;
    private final LockStrategy currentStrategy;

    public DefaultDistributedLocker(LockStorageProvider storageProvider) {
        this(storageProvider, LockStrategy.DATABASE);
    }

    public DefaultDistributedLocker(LockStorageProvider storageProvider, LockStrategy strategy) {
        this(storageProvider, new WatchdogCoordinator(storageProvider), LockConfig.defaultConfig(), strategy);
    }

    public DefaultDistributedLocker(LockStorageProvider storageProvider,
                                    WatchdogCoordinator watchdogCoordinator,
                                    LockConfig defaultConfig) {
        this(storageProvider, watchdogCoordinator, defaultConfig, LockStrategy.DATABASE);
    }

    public DefaultDistributedLocker(LockStorageProvider storageProvider,
                                    WatchdogCoordinator watchdogCoordinator,
                                    LockConfig defaultConfig,
                                    LockStrategy strategy) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.watchdogCoordinator = Objects.requireNonNull(watchdogCoordinator, "watchdogCoordinator must not be null");
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
        this.currentStrategy = strategy != null ? strategy : LockStrategy.DATABASE;
    }

    public LockStrategy getCurrentStrategy() {
        return currentStrategy;
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
        if (snapshot.strategy() != null
                && !snapshot.strategy().name().equalsIgnoreCase(currentStrategy.name())) {
            throw new IllegalStateException("Locker is bound to strategy [" + currentStrategy.name()
                    + "] but operation requested [" + snapshot.strategy().name() + "]");
        }

        LockConfig config = resolveConfig(snapshot);
        List<String> sortedKeys = snapshot.qualifiedKeys();
        Set<String> threadHeldKeys = HELD_LOCK_KEYS.get();
        List<String> reentrantKeys = sortedKeys.stream()
                .map(this::strategyScopedKey)
                .filter(threadHeldKeys::contains)
                .toList();
        if (!reentrantKeys.isEmpty()) {
            throw new LockAcquisitionException(reentrantKeys.get(0),
                    "Reentrant locking is not supported for keys " + reentrantKeys);
        }

        String owner = LockOwner.newOwner();
        long waitTimeoutMillis = config.getWaitTimeoutMillis();
        long leaseMillis = config.getLeaseMillis();
        long waitBudgetNanos = TimeUnit.MILLISECONDS.toNanos(waitTimeoutMillis);
        long waitStartNanos = System.nanoTime();
        List<String> acquiredKeys = new ArrayList<>(sortedKeys.size());
        AdaptiveBackoff backoff = new AdaptiveBackoff();

        try {
            for (String lockKey : sortedKeys) {
                boolean acquiredThis = false;
                backoff.reset();

                while (true) {
                    if (storageProvider.tryAcquire(lockKey, owner, leaseMillis)) {
                        acquiredKeys.add(lockKey);
                        if (config.isWatchdogEnabled()) {
                            watchdogCoordinator.startRenew(lockKey, owner, leaseMillis);
                        }
                        acquiredThis = true;
                        break;
                    }

                    long elapsedNanos = System.nanoTime() - waitStartNanos;
                    long remainingNanos = waitBudgetNanos - elapsedNanos;
                    if (remainingNanos <= 0) {
                        break;
                    }

                    try {
                        backoff.backoff(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new LockAcquisitionException(lockKey,
                                "Interrupted while waiting for lock [" + lockKey + "]", exception);
                    }
                }

                if (!acquiredThis) {
                    log.warn("Failed to acquire all locks [{}], rolling back acquired [{}]",
                            sortedKeys, acquiredKeys);
                    cleanupKeys(acquiredKeys, owner, config.isWatchdogEnabled());
                    acquiredKeys.clear();
                    return LockOutcome.timeout(sortedKeys, waitTimeoutMillis);
                }
            }

            // 批量获取可能耗时较长；执行前续期并确认全部锁仍属于本次 acquisition。
            for (String key : acquiredKeys) {
                if (!storageProvider.renew(key, owner, leaseMillis)) {
                    throw new LockAcquisitionException(key,
                            "Lock ownership was lost before business execution for [" + key + "]");
                }
            }

            for (String key : acquiredKeys) {
                threadHeldKeys.add(strategyScopedKey(key));
            }
            return LockOutcome.acquired(action.get());
        } finally {
            for (String key : acquiredKeys) {
                threadHeldKeys.remove(strategyScopedKey(key));
            }
            if (threadHeldKeys.isEmpty()) {
                HELD_LOCK_KEYS.remove();
            }
            if (!acquiredKeys.isEmpty()) {
                cleanupKeys(acquiredKeys, owner, config.isWatchdogEnabled());
            }
        }
    }

    private LockConfig resolveConfig(LockOperation.Snapshot snapshot) {
        long waitTimeout = snapshot.waitTimeoutMillis() != null
                ? snapshot.waitTimeoutMillis() : defaultConfig.getWaitTimeoutMillis();
        long leaseTime = snapshot.leaseMillis() != null
                ? snapshot.leaseMillis() : defaultConfig.getLeaseMillis();
        boolean watchdog = snapshot.watchdogEnabled() != null
                ? snapshot.watchdogEnabled() : defaultConfig.isWatchdogEnabled();
        return new LockConfig(waitTimeout, leaseTime, watchdog);
    }

    private String strategyScopedKey(String lockKey) {
        return currentStrategy.name().toUpperCase(Locale.ROOT) + "|" + lockKey;
    }

    private void cleanupKeys(List<String> keys, String owner, boolean watchdogEnabled) {
        if (watchdogEnabled) {
            for (String key : keys) {
                watchdogCoordinator.stopRenew(key, owner);
            }
        }
        for (int index = keys.size() - 1; index >= 0; index--) {
            String key = keys.get(index);
            try {
                if (!storageProvider.release(key, owner)) {
                    log.warn("Lock [{}] was not released because ownership had already been lost", key);
                }
            } catch (Throwable throwable) {
                log.error("Failed to release lock [{}] for owner [{}]", key, owner, throwable);
            }
        }
    }
}
