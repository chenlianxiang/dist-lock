package com.distlock.core.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
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

    record Snapshot(Class<?> namespace,
                    List<Object> businessKeys,
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

    interface NamespaceCarrier {
        Class<?> lockNamespace();
    }

    static <T> LockOperation create(Object resourceOrResources,
                                    Function<T, ?> keyExtractor,
                                    Executor executor) {
        Objects.requireNonNull(resourceOrResources, "resourceOrResources must not be null");
        Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");
        Collection<?> resources = resourceOrResources instanceof Collection<?> collection
                ? collection : Collections.singletonList(resourceOrResources);
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("resources must not be empty");
        }
        TreeMap<String, Object> keys = new TreeMap<>();
        Class<?> namespace = null;
        for (Object resource : resources) {
            if (resource == null) {
                throw new IllegalArgumentException("resources must not contain null elements");
            }
            Class<?> resourceNamespace = resolveNamespace(resource);
            if (namespace == null) {
                namespace = resourceNamespace;
            } else if (!namespace.equals(resourceNamespace)) {
                throw new IllegalArgumentException("resources must share one namespace, but found ["
                        + namespace.getName() + "] and [" + resourceNamespace.getName() + "]");
            }
            Object key;
            try {
                @SuppressWarnings("unchecked")
                T typedResource = (T) resource;
                key = keyExtractor.apply(typedResource);
            } catch (ClassCastException exception) {
                throw new IllegalArgumentException("keyExtractor does not accept resource type ["
                        + resource.getClass().getName() + "]", exception);
            }
            keys.put(qualify(namespace, key), key);
        }
        return fromQualified(namespace, List.copyOf(keys.values()), List.copyOf(keys.keySet()), executor);
    }

    private static LockOperation fromQualified(Class<?> namespace,
                                               List<Object> businessKeys,
                                               List<String> qualifiedKeys,
                                               Executor executor) {
        Snapshot snapshot = new Snapshot(namespace, businessKeys, qualifiedKeys,
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

    /**
     * 为同一实体划分独立锁域。普通场景无需配置，默认使用资源对象的用户类。
     */
    public LockOperation scope(Class<?> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        TreeMap<String, Object> keys = new TreeMap<>();
        for (Object key : snapshot.businessKeys()) {
            keys.put(qualify(namespace, key), key);
        }
        Snapshot scoped = new Snapshot(namespace, List.copyOf(keys.values()), List.copyOf(keys.keySet()),
                snapshot.strategy(), snapshot.waitTimeoutMillis(), snapshot.leaseMillis(),
                snapshot.watchdogEnabled());
        return new LockOperation(scoped, executor);
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

    private static String qualify(Class<?> namespace, Object key) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        String encodedKey = encodeKey(key);
        String qualified = "dist-lock:v1:" + namespace.getName() + ":"
                + key.getClass().getName() + ":" + encodedKey;
        if (qualified.length() > MAX_QUALIFIED_KEY_LENGTH) {
            throw new IllegalArgumentException("qualified lock key exceeds "
                    + MAX_QUALIFIED_KEY_LENGTH + " characters: " + qualified.length());
        }
        return qualified;
    }

    private static Class<?> resolveNamespace(Object resource) {
        if (resource instanceof NamespaceCarrier carrier) {
            return Objects.requireNonNull(carrier.lockNamespace(), "carried namespace must not be null");
        }
        Class<?> type = resource.getClass();
        while (type.getSuperclass() != null
                && type.getSuperclass() != Object.class
                && (type.getName().contains("$$") || type.getName().contains("$HibernateProxy$"))) {
            type = type.getSuperclass();
        }
        return type;
    }

    private static String encodeKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("lock key must not be null");
        }
        if (key instanceof CharSequence
                || key instanceof Number
                || key instanceof java.util.UUID
                || key instanceof Enum<?>
                || key instanceof Boolean
                || key instanceof Character) {
            String encoded = key.toString();
            if (encoded.isBlank()) {
                throw new IllegalArgumentException("lock key must not be blank");
            }
            return encoded;
        }
        throw new IllegalArgumentException("lock key type [" + key.getClass().getName()
                + "] has no stable encoding; extract a String, Number, UUID, enum, boolean or character key");
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
