package com.distlock.core.api;

import com.distlock.core.backoff.AdaptiveBackoff;
import com.distlock.core.context.LockOwner;
import com.distlock.core.exception.LockAcquisitionException;
import com.distlock.core.exception.LockTimeoutException;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.watchdog.WatchdogCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 分布式锁核心默认实现。
 * <p>
 * 支持默认固定友好提示、特制错误文案、特制业务异常工厂、函数式兜底降级等多维度失败策略。
 */
public class DefaultDistributedLocker implements DistributedLocker {

    private static final Logger log = LoggerFactory.getLogger(DefaultDistributedLocker.class);

    private final LockStorageProvider storageProvider;
    private final WatchdogCoordinator watchdogCoordinator;
    private final LockConfig defaultConfig;
    private final LockStrategy currentStrategy;
    private Function<LockStrategy, DistributedLocker> strategyRouter;
    private static final ThreadLocal<Set<String>> HELD_LOCK_KEYS = ThreadLocal.withInitial(HashSet::new);

    public DefaultDistributedLocker(LockStorageProvider storageProvider) {
        this(storageProvider, LockStrategy.DATABASE);
    }

    public DefaultDistributedLocker(LockStorageProvider storageProvider, LockStrategy strategy) {
        this(storageProvider, new WatchdogCoordinator(storageProvider), LockConfig.defaultConfig(), strategy);
    }

    public DefaultDistributedLocker(LockStorageProvider storageProvider, WatchdogCoordinator watchdogCoordinator, LockConfig defaultConfig) {
        this(storageProvider, watchdogCoordinator, defaultConfig, LockStrategy.DATABASE);
    }

    public DefaultDistributedLocker(LockStorageProvider storageProvider, WatchdogCoordinator watchdogCoordinator, LockConfig defaultConfig, LockStrategy strategy) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.watchdogCoordinator = Objects.requireNonNull(watchdogCoordinator, "watchdogCoordinator must not be null");
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
        this.currentStrategy = strategy != null ? strategy : LockStrategy.DATABASE;
    }

    public LockStrategy getCurrentStrategy() {
        return currentStrategy;
    }

    public void setStrategyRouter(Function<LockStrategy, DistributedLocker> strategyRouter) {
        this.strategyRouter = strategyRouter;
    }

    @Override
    public DistributedLocker use(LockStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (strategyRouter != null) {
            return strategyRouter.apply(strategy);
        }
        if (strategy.name().equalsIgnoreCase(currentStrategy.name())) {
            return this;
        }
        throw new UnsupportedOperationException("Standalone DefaultDistributedLocker is bound to strategy ["
                + currentStrategy.name() + "]. Please configure RoutingDistributedLocker to switch strategies dynamically.");
    }

    // =========================================================================
    // 1. 单对象重载实现
    // =========================================================================

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, Function<T, R> action) {
        return doLockSingle(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, defaultHandler(), action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, Consumer<T> action) {
        doLockSingleConsumer(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, defaultHandler(), action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, String errorMessage, Function<T, R> action) {
        return doLockSingle(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, messageHandler(errorMessage), action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, String errorMessage, Consumer<T> action) {
        doLockSingleConsumer(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, messageHandler(errorMessage), action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Function<T, R> action) {
        return doLockSingle(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<T> action) {
        doLockSingleConsumer(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, Function<T, R> action, Function<T, R> fallback) {
        return doLockSingle(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, singleFallbackHandler(fallback), action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, Consumer<T> action, Consumer<T> fallback) {
        doLockSingleConsumer(data, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, singleFallbackConsumerHandler(fallback), action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Function<T, R> action) {
        return doLockSingle(data, keyExtractor, timeout, unit, messageHandler(errorMessage), action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Consumer<T> action) {
        doLockSingleConsumer(data, keyExtractor, timeout, unit, messageHandler(errorMessage), action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Function<T, R> action) {
        return doLockSingle(data, keyExtractor, timeout, unit, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<T> action) {
        doLockSingleConsumer(data, keyExtractor, timeout, unit, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Function<T, R> action, Function<T, R> fallback) {
        return doLockSingle(data, keyExtractor, timeout, unit, singleFallbackHandler(fallback), action);
    }

    @Override
    public <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Consumer<T> action, Consumer<T> fallback) {
        doLockSingleConsumer(data, keyExtractor, timeout, unit, singleFallbackConsumerHandler(fallback), action);
    }

    // 单对象委托执行
    private <T, R> R doLockSingle(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit,
                                  FailureHandler<List<T>, R> failureHandler, Function<T, R> action) {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(action, "action must not be null");
        LockConfig config = defaultConfig.withTimeout(timeout, unit);
        return executeBatch(
                Collections.singletonList(data),
                keyExtractor,
                list -> action.apply(list.get(0)),
                failureHandler,
                config
        );
    }

    private <T> void doLockSingleConsumer(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit,
                                          FailureHandler<List<T>, Void> failureHandler, Consumer<T> action) {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(action, "action must not be null");
        LockConfig config = defaultConfig.withTimeout(timeout, unit);
        executeBatch(
                Collections.singletonList(data),
                keyExtractor,
                list -> {
                    action.accept(list.get(0));
                    return null;
                },
                failureHandler,
                config
        );
    }

    // =========================================================================
    // 2. 集合批量重载实现
    // =========================================================================

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Function<C, R> action) {
        return doLockBatch(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, defaultHandler(), action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Consumer<C> action) {
        doLockBatchConsumer(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, defaultHandler(), action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, String errorMessage, Function<C, R> action) {
        return doLockBatch(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, messageHandler(errorMessage), action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, String errorMessage, Consumer<C> action) {
        doLockBatchConsumer(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, messageHandler(errorMessage), action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Function<C, R> action) {
        return doLockBatch(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<C> action) {
        doLockBatchConsumer(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Function<C, R> action, Function<C, R> fallback) {
        return doLockBatch(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, fallbackHandler(fallback), action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Consumer<C> action, Consumer<C> fallback) {
        doLockBatchConsumer(dataList, keyExtractor, defaultConfig.getWaitTimeoutMillis(), TimeUnit.MILLISECONDS, fallbackConsumerHandler(fallback), action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Function<C, R> action) {
        return doLockBatch(dataList, keyExtractor, timeout, unit, messageHandler(errorMessage), action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Consumer<C> action) {
        doLockBatchConsumer(dataList, keyExtractor, timeout, unit, messageHandler(errorMessage), action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Function<C, R> action) {
        return doLockBatch(dataList, keyExtractor, timeout, unit, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<C> action) {
        doLockBatchConsumer(dataList, keyExtractor, timeout, unit, supplierHandler(exceptionSupplier), action);
    }

    @Override
    public <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Function<C, R> action, Function<C, R> fallback) {
        return doLockBatch(dataList, keyExtractor, timeout, unit, fallbackHandler(fallback), action);
    }

    @Override
    public <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Consumer<C> action, Consumer<C> fallback) {
        doLockBatchConsumer(dataList, keyExtractor, timeout, unit, fallbackConsumerHandler(fallback), action);
    }

    // 集合批量委托执行
    private <T, C extends Collection<T>, R> R doLockBatch(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit,
                                                          FailureHandler<C, R> failureHandler, Function<C, R> action) {
        Objects.requireNonNull(dataList, "dataList must not be null");
        Objects.requireNonNull(action, "action must not be null");
        LockConfig config = defaultConfig.withTimeout(timeout, unit);
        return executeBatch(dataList, keyExtractor, action, failureHandler, config);
    }

    private <T, C extends Collection<T>> void doLockBatchConsumer(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit,
                                                                  FailureHandler<C, Void> failureHandler, Consumer<C> action) {
        Objects.requireNonNull(dataList, "dataList must not be null");
        Objects.requireNonNull(action, "action must not be null");
        LockConfig config = defaultConfig.withTimeout(timeout, unit);
        executeBatch(
                dataList,
                keyExtractor,
                c -> {
                    action.accept(c);
                    return null;
                },
                failureHandler,
                config
        );
    }

    // =========================================================================
    // 统一失败策略工厂 (Failure Handlers)
    // =========================================================================

    @FunctionalInterface
    private interface FailureHandler<C, R> {
        R handle(C collection, String lockKey, long timeoutMillis);
    }

    private static <C, R> FailureHandler<C, R> defaultHandler() {
        return (col, key, timeout) -> {
            throw new LockTimeoutException(key, timeout, LockTimeoutException.DEFAULT_MESSAGE);
        };
    }

    private static <C, R> FailureHandler<C, R> messageHandler(String message) {
        return (col, key, timeout) -> {
            throw new LockTimeoutException(key, timeout, message);
        };
    }

    private static <C, R> FailureHandler<C, R> supplierHandler(Supplier<? extends RuntimeException> supplier) {
        return (col, key, timeout) -> {
            if (supplier != null) {
                RuntimeException ex = supplier.get();
                if (ex != null) {
                    throw ex;
                }
            }
            throw new LockTimeoutException(key, timeout, LockTimeoutException.DEFAULT_MESSAGE);
        };
    }

    private static <T, R> FailureHandler<List<T>, R> singleFallbackHandler(Function<T, R> fallback) {
        return (col, key, timeout) -> {
            if (fallback != null) {
                return fallback.apply(col.get(0));
            }
            throw new LockTimeoutException(key, timeout, LockTimeoutException.DEFAULT_MESSAGE);
        };
    }

    private static <T> FailureHandler<List<T>, Void> singleFallbackConsumerHandler(Consumer<T> fallback) {
        return (col, key, timeout) -> {
            if (fallback != null) {
                fallback.accept(col.get(0));
                return null;
            }
            throw new LockTimeoutException(key, timeout, LockTimeoutException.DEFAULT_MESSAGE);
        };
    }

    private static <C, R> FailureHandler<C, R> fallbackHandler(Function<C, R> fallback) {
        return (col, key, timeout) -> {
            if (fallback != null) {
                return fallback.apply(col);
            }
            throw new LockTimeoutException(key, timeout, LockTimeoutException.DEFAULT_MESSAGE);
        };
    }

    private static <C> FailureHandler<C, Void> fallbackConsumerHandler(Consumer<C> fallback) {
        return (col, key, timeout) -> {
            if (fallback != null) {
                fallback.accept(col);
                return null;
            }
            throw new LockTimeoutException(key, timeout, LockTimeoutException.DEFAULT_MESSAGE);
        };
    }

    // =========================================================================
    // 全系统唯一的原子执行核心引擎
    // =========================================================================
    private <T, C extends Collection<T>, R> R executeBatch(C collection,
                                                          Function<T, ?> keyExtractor,
                                                          Function<C, R> action,
                                                          FailureHandler<C, R> failureHandler,
                                                          LockConfig config) {
        Objects.requireNonNull(collection, "collection must not be null");
        Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");

        if (collection.isEmpty()) {
            return action.apply(collection);
        }

        // 1. 每个对象独立生成完整 Key，严格校验后去重并排序（消除分布式死锁）
        List<String> sortedKeys = collection.stream()
                .map(item -> buildLockKey(item, keyExtractor))
                .distinct()
                .sorted()
                .toList();

        if (sortedKeys.isEmpty()) {
            throw new IllegalArgumentException("Extracted lock keys must not be empty for: " + collection);
        }

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
        boolean allAcquired = true;

        try {
            for (String lockKey : sortedKeys) {
                boolean acquiredThis = false;
                backoff.reset();

                while (true) {
                    acquiredThis = storageProvider.tryAcquire(lockKey, owner, leaseMillis);
                    if (acquiredThis) {
                        acquiredKeys.add(lockKey);
                        if (config.isWatchdogEnabled()) {
                            watchdogCoordinator.startRenew(lockKey, owner, leaseMillis);
                        }
                        break;
                    }
                    long elapsedNanos = System.nanoTime() - waitStartNanos;
                    long remainingNanos = waitBudgetNanos - elapsedNanos;
                    if (remainingNanos <= 0) {
                        allAcquired = false;
                        break;
                    }
                    try {
                        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                        backoff.backoff(remainingMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new LockAcquisitionException(lockKey, "Interrupted while waiting for lock [" + lockKey + "]", e);
                    }
                }

                if (!acquiredThis) {
                    allAcquired = false;
                    break;
                }
            }

            if (!allAcquired) {
                log.warn("Failed to acquire all locks for keys [{}], rolling back acquired [{}]", sortedKeys, acquiredKeys);
                cleanupKeys(acquiredKeys, owner, config.isWatchdogEnabled());
                acquiredKeys.clear();

                // 统一交由 failureHandler 处理（抛出默认友好异常、特制异常、特制文案或函数式兜底）
                return failureHandler.handle(collection, "lock[" + sortedKeys + "]", waitTimeoutMillis);
            }

            // 批量获取可能耗时较长，执行业务前原子续期一次，同时确认所有权仍然有效。
            for (String key : acquiredKeys) {
                if (!storageProvider.renew(key, owner, leaseMillis)) {
                    throw new LockAcquisitionException(key,
                            "Lock ownership was lost before business execution for [" + key + "]");
                }
            }

            for (String key : acquiredKeys) {
                threadHeldKeys.add(strategyScopedKey(key));
            }
            return action.apply(collection);

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

    private <T> String buildLockKey(T item, Function<T, ?> keyExtractor) {
        if (item == null) {
            throw new IllegalArgumentException("Lock collection must not contain null elements");
        }
        Object extractedKey = keyExtractor.apply(item);
        if (extractedKey == null) {
            throw new IllegalArgumentException("Extracted lock key must not be null for " + item.getClass().getName());
        }
        String businessKey = extractedKey.toString();
        if (businessKey.isBlank()) {
            throw new IllegalArgumentException("Extracted lock key must not be blank for " + item.getClass().getName());
        }
        return resolveNamespace(item) + ":" + businessKey;
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
        rollbackKeys(keys, owner);
    }

    private void rollbackKeys(List<String> keys, String owner) {
        for (int i = keys.size() - 1; i >= 0; i--) {
            String key = keys.get(i);
            try {
                storageProvider.release(key, owner);
            } catch (Throwable t) {
                log.error("Failed to release lock [{}] for owner [{}]", key, owner, t);
            }
        }
    }

    /**
     * 解析实体对象的全限定类名，天然保证分布式锁命名空间的全局唯一性；
     * 同时自动检测并剥离 Spring CGLIB / ByteBuddy 等动态代理生成的后缀。
     */
    private String resolveNamespace(Object obj) {
        if (obj == null) {
            return "Unknown";
        }
        Class<?> clazz = obj.getClass();
        String className = clazz.getName();
        int proxyIndex = className.indexOf("$$");
        return proxyIndex > 0 ? className.substring(0, proxyIndex) : className;
    }
}
