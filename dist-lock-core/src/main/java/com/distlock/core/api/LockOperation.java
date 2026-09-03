package com.distlock.core.api;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 锁操作的链式配置器。配置方法只生成新的不可变操作对象，直到调用 call/run/tryCall 才会执行。
 */
public final class LockOperation {

    private static final int MAX_QUALIFIED_KEY_LENGTH = 255;

    @FunctionalInterface
    interface Executor {
        LockOutcome<?> execute(Snapshot snapshot, Supplier<?> action);
    }

    record Snapshot(String namespace,
                    List<String> businessKeys,
                    List<String> qualifiedKeys,
                    LockStrategy strategy,
                    Long waitTimeoutMillis,
                    Long leaseMillis,
                    Boolean watchdogEnabled) {
    }

    private final Snapshot snapshot;
    private final Executor executor;

    private LockOperation(Snapshot snapshot, Executor executor) {
        this.snapshot = snapshot;
        this.executor = executor;
    }

    static LockOperation single(String namespace, Object key, Executor executor) {
        return multiple(namespace, List.of(requireKey(key)), executor);
    }

    static <T> LockOperation batch(String namespace,
                                   Collection<T> resources,
                                   Function<T, ?> keyExtractor,
                                   Executor executor) {
        Objects.requireNonNull(resources, "resources must not be null");
        Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("resources must not be empty");
        }
        TreeSet<String> keys = new TreeSet<>();
        for (T resource : resources) {
            if (resource == null) {
                throw new IllegalArgumentException("resources must not contain null elements");
            }
            keys.add(requireKey(keyExtractor.apply(resource)));
        }
        return multiple(namespace, List.copyOf(keys), executor);
    }

    private static LockOperation multiple(String namespace, List<String> businessKeys, Executor executor) {
        String normalizedNamespace = requireNamespace(namespace);
        List<String> qualifiedKeys = businessKeys.stream()
                .map(key -> qualify(normalizedNamespace, key))
                .sorted()
                .toList();
        Snapshot snapshot = new Snapshot(normalizedNamespace, List.copyOf(businessKeys), qualifiedKeys,
                null, null, null, null);
        return new LockOperation(snapshot, Objects.requireNonNull(executor, "executor must not be null"));
    }

    public LockOperation strategy(LockStrategy strategy) {
        return copy(Objects.requireNonNull(strategy, "strategy must not be null"),
                snapshot.waitTimeoutMillis(), snapshot.leaseMillis(), snapshot.watchdogEnabled());
    }

    public LockOperation waitTimeout(Duration waitTimeout) {
        return copy(snapshot.strategy(), toMillis(waitTimeout, true, "waitTimeout"),
                snapshot.leaseMillis(), snapshot.watchdogEnabled());
    }

    public LockOperation leaseTime(Duration leaseTime) {
        return copy(snapshot.strategy(), snapshot.waitTimeoutMillis(),
                toMillis(leaseTime, false, "leaseTime"), snapshot.watchdogEnabled());
    }

    public LockOperation watchdog(boolean enabled) {
        return copy(snapshot.strategy(), snapshot.waitTimeoutMillis(), snapshot.leaseMillis(), enabled);
    }

    public <R> R call(Supplier<R> action) {
        return tryCall(action).getOrThrow();
    }

    public void run(Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        call(() -> {
            action.run();
            return null;
        });
    }

    public <R> LockOutcome<R> tryCall(Supplier<R> action) {
        Objects.requireNonNull(action, "action must not be null");
        @SuppressWarnings("unchecked")
        LockOutcome<R> outcome = (LockOutcome<R>) executor.execute(snapshot, action);
        return outcome;
    }

    private LockOperation copy(LockStrategy strategy,
                               Long waitTimeoutMillis,
                               Long leaseMillis,
                               Boolean watchdogEnabled) {
        return new LockOperation(new Snapshot(snapshot.namespace(), snapshot.businessKeys(), snapshot.qualifiedKeys(),
                strategy, waitTimeoutMillis, leaseMillis, watchdogEnabled), executor);
    }

    private static String requireNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return namespace.trim();
    }

    private static String requireKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("lock key must not be null");
        }
        String businessKey = key.toString();
        if (businessKey.isBlank()) {
            throw new IllegalArgumentException("lock key must not be blank");
        }
        return businessKey;
    }

    private static String qualify(String namespace, String businessKey) {
        String qualified = namespace + ":" + businessKey;
        if (qualified.length() > MAX_QUALIFIED_KEY_LENGTH) {
            throw new IllegalArgumentException("qualified lock key exceeds "
                    + MAX_QUALIFIED_KEY_LENGTH + " characters: " + qualified.length());
        }
        return qualified;
    }

    private static long toMillis(Duration duration, boolean zeroAllowed, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large", exception);
        }
        if (millis < 0 || (!zeroAllowed && millis == 0)) {
            throw new IllegalArgumentException(name + (zeroAllowed
                    ? " must be greater than or equal to 0"
                    : " must be greater than 0"));
        }
        return millis;
    }
}
