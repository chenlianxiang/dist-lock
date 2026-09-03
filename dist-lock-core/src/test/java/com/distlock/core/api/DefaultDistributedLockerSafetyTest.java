package com.distlock.core.api;

import com.distlock.core.exception.LockAcquisitionException;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.spi.LockAcquisition;
import com.distlock.core.watchdog.WatchdogCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final class InventoryLock {}
    private static final class PaymentLock {}
    private static final class CancellationLock {}

    @Test
    void rejectsReentrantLockingAndReleasesOuterLock() {
        Order order = new Order("42");
        assertThatThrownBy(() -> locker.lock(order, Order::id).call(() ->
                locker.lock(order, Order::id).call(() -> "unexpected")))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Reentrant locking is not supported");

        assertThat(storage.owners).isEmpty();
    }

    @Test
    void rejectsInvalidResourcesBeforeBusinessExecution() {
        assertThatThrownBy(() -> locker.lock(
                java.util.Arrays.asList(new Order("1"), null), Order::id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain null");

        assertThatThrownBy(() -> locker.lock(new Order(" "), Order::id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThat(storage.owners).isEmpty();
    }

    @Test
    void batchKeysAreDeduplicatedAndGloballySorted() {
        locker.lock(List.of("SKU-2", "SKU-1", "SKU-2"), Function.identity())
                .scope(InventoryLock.class)
                .call(() -> true);

        assertThat(storage.acquireOrder).hasSize(2).isSorted();
        assertThat(storage.acquireOrder).allMatch(key -> key.startsWith("dist-lock:v1:"));
    }

    @Test
    void chainConfigurationIsImmutableAndValidated() {
        LockOperation base = locker.lock(new Order("42"), Order::id);
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
        locker.lock(new Order("42"), Order::id).call(() -> true);
        String physicalKey = storage.acquireOrder.get(0);
        storage.owners.put(physicalKey, "external-owner");
        AtomicBoolean actionCalled = new AtomicBoolean();

        LockOutcome<String> outcome = locker.lock(new Order("42"), Order::id)
                .waitTimeout(Duration.ZERO)
                .tryCall(() -> {
                    actionCalled.set(true);
                    return "unexpected";
                });

        assertThat(outcome.status()).isEqualTo(LockOutcome.Status.TIMEOUT);
        assertThat(outcome.orElse("fallback")).isEqualTo("fallback");
        assertThat(actionCalled).isFalse();
    }

    @Test
    void classNamespaceAndKeyTypePreventAccidentalCollisions() {
        locker.lock("42", Function.identity()).scope(PaymentLock.class).call(() -> true);
        locker.lock("42", Function.identity()).scope(CancellationLock.class).call(() -> true);
        locker.lock(42L, Function.identity()).scope(PaymentLock.class).call(() -> true);

        assertThat(storage.acquireOrder).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void singleObjectAndCollectionUseTheSameLockPipeline() {
        Order order = new Order("42");

        locker.lock(order, Order::id).call(() -> true);
        locker.lock(List.of(order), Order::id).call(() -> true);

        assertThat(storage.acquireOrder).hasSize(2);
        assertThat(storage.acquireOrder.get(0)).isEqualTo(storage.acquireOrder.get(1));
    }

    @Test
    void exposesMonotonicFencingTokenThroughLockHandle() {
        long first = locker.lock(new Order("42"), Order::id)
                .callWithHandle(LockHandle::fencingToken);
        long second = locker.lock(new Order("42"), Order::id)
                .callWithHandle(LockHandle::fencingToken);

        assertThat(second).isGreaterThan(first);
    }

    private static final class InMemoryStorageProvider implements LockStorageProvider {
        private final Map<String, String> owners = new ConcurrentHashMap<>();
        private final List<String> acquireOrder = new CopyOnWriteArrayList<>();
        private final AtomicLong fencingTokens = new AtomicLong();

        @Override
        public LockAcquisition tryAcquire(String lockKey, String owner, long leaseMillis) {
            boolean acquired = owners.putIfAbsent(lockKey, owner) == null;
            if (acquired) {
                acquireOrder.add(lockKey);
                return LockAcquisition.acquired(fencingTokens.incrementAndGet());
            }
            return LockAcquisition.contended();
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
