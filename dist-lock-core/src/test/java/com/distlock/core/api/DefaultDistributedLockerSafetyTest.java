package com.distlock.core.api;

import com.distlock.core.exception.LockAcquisitionException;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.watchdog.WatchdogCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultDistributedLockerSafetyTest {

    private final InMemoryStorageProvider storage = new InMemoryStorageProvider();
    private final DefaultDistributedLocker locker = new DefaultDistributedLocker(
            storage,
            new WatchdogCoordinator(storage),
            LockConfig.of(20, TimeUnit.MILLISECONDS, 1, TimeUnit.SECONDS, false)
    );

    record Order(String id) {}

    @Test
    void rejectsReentrantLockingAndReleasesOuterLock() {
        assertThatThrownBy(() -> locker.lock("order", "42").call(() ->
                locker.lock("order", "42").call(() -> "unexpected")))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Reentrant locking is not supported");

        assertThat(storage.owners).isEmpty();
    }

    @Test
    void rejectsInvalidResourcesBeforeBusinessExecution() {
        assertThatThrownBy(() -> locker.locks(
                "order", java.util.Arrays.asList(new Order("1"), null), Order::id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain null");

        assertThatThrownBy(() -> locker.lock("order", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThat(storage.owners).isEmpty();
    }

    @Test
    void batchKeysAreDeduplicatedAndGloballySorted() {
        locker.locks("inventory", List.of("SKU-2", "SKU-1", "SKU-2"), Function.identity())
                .call(() -> true);

        assertThat(storage.acquireOrder)
                .containsExactly("inventory:SKU-1", "inventory:SKU-2");
    }

    @Test
    void chainConfigurationIsImmutableAndValidated() {
        LockOperation base = locker.lock("order", "42");
        LockOperation configured = base
                .waitTimeout(Duration.ZERO)
                .leaseTime(Duration.ofSeconds(5))
                .watchdog(false);

        assertThat(configured).isNotSameAs(base);
        assertThatThrownBy(() -> base.leaseTime(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockConfig(-1, 1000, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tryCallSeparatesContentionFromBusinessExecution() {
        storage.owners.put("order:42", "external-owner");
        AtomicBoolean actionCalled = new AtomicBoolean();

        LockOutcome<String> outcome = locker.lock("order", "42")
                .waitTimeout(Duration.ZERO)
                .tryCall(() -> {
                    actionCalled.set(true);
                    return "unexpected";
                });

        assertThat(outcome.status()).isEqualTo(LockOutcome.Status.TIMEOUT);
        assertThat(outcome.orElse("fallback")).isEqualTo("fallback");
        assertThat(actionCalled).isFalse();
    }

    private static final class InMemoryStorageProvider implements LockStorageProvider {
        private final Map<String, String> owners = new ConcurrentHashMap<>();
        private final List<String> acquireOrder = new CopyOnWriteArrayList<>();

        @Override
        public boolean tryAcquire(String lockKey, String owner, long leaseMillis) {
            boolean acquired = owners.putIfAbsent(lockKey, owner) == null;
            if (acquired) {
                acquireOrder.add(lockKey);
            }
            return acquired;
        }

        @Override
        public boolean release(String lockKey, String owner) {
            return owners.remove(lockKey, owner);
        }

        @Override
        public boolean renew(String lockKey, String owner, long leaseMillis) {
            return owner.equals(owners.get(lockKey));
        }

        @Override
        public long getStorageTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
