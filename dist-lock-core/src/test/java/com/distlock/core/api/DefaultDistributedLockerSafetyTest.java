package com.distlock.core.api;

import com.distlock.core.exception.LockAcquisitionException;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.watchdog.WatchdogCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    record Account(String id) {}

    @Test
    void rejectsReentrantLockingAndReleasesOuterLock() {
        Order order = new Order("42");

        assertThatThrownBy(() -> locker.lock(order, Order::id, outer ->
                locker.lock(order, Order::id, inner -> "unexpected")))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Reentrant locking is not supported");

        assertThat(storage.owners).isEmpty();
    }

    @Test
    void rejectsNullElementsAndBlankKeysBeforeBusinessExecution() {
        AtomicBoolean actionCalled = new AtomicBoolean();

        assertThatThrownBy(() -> locker.lock(
                java.util.Arrays.asList(new Order("1"), null),
                Order::id,
                items -> actionCalled.getAndSet(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain null");

        assertThatThrownBy(() -> locker.lock(
                List.of(new Order(" ")),
                Order::id,
                items -> actionCalled.getAndSet(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThat(actionCalled).isFalse();
        assertThat(storage.owners).isEmpty();
    }

    @Test
    void buildsNamespaceForEveryBatchElement() {
        List<Object> resources = List.of(new Order("1"), new Account("1"));

        locker.lock(resources, resource -> "1", ignored -> true);

        assertThat(storage.acquiredKeys)
                .contains(Order.class.getName() + ":1", Account.class.getName() + ":1");
    }

    @Test
    void validatesTimeoutAndLeaseConfiguration() {
        assertThatThrownBy(() -> new LockConfig(-1, 1000, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockConfig(1000, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class InMemoryStorageProvider implements LockStorageProvider {
        private final Map<String, String> owners = new ConcurrentHashMap<>();
        private final java.util.Set<String> acquiredKeys = ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryAcquire(String lockKey, String owner, long leaseMillis) {
            boolean acquired = owners.putIfAbsent(lockKey, owner) == null;
            if (acquired) {
                acquiredKeys.add(lockKey);
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
