package com.distlock.core.watchdog;

import com.distlock.core.spi.LockAcquisition;
import com.distlock.core.spi.LockStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WatchdogCoordinatorTest {

    private WatchdogCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    @Test
    void recordsLostStateAndCancelsTaskWhenOwnershipIsGone() throws Exception {
        TestStorage storage = new TestStorage();
        storage.renewed.set(false);
        coordinator = new WatchdogCoordinator(storage, 2);

        WatchdogLease lease = coordinator.startRenew("order", "owner", 30);

        await(Duration.ofSeconds(1), () -> lease.state() == WatchdogLease.State.LOST);
        assertThat(lease.state()).isEqualTo(WatchdogLease.State.LOST);
        assertThat(coordinator.activeTaskCount()).isZero();
    }

    @Test
    void stopsRenewalWithoutLosingOwnership() {
        coordinator = new WatchdogCoordinator(new TestStorage(), 2);
        WatchdogLease lease = coordinator.startRenew("order", "owner", 300);

        coordinator.stopRenew("order", "owner");

        assertThat(lease.state()).isEqualTo(WatchdogLease.State.STOPPED);
        assertThat(coordinator.activeTaskCount()).isZero();
    }

    private static void await(Duration timeout, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.matches() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean matches() throws Exception;
    }

    private static final class TestStorage implements LockStorageProvider {
        private final AtomicBoolean renewed = new AtomicBoolean(true);

        @Override
        public LockAcquisition tryAcquire(String lockKey, String owner, long leaseMillis) {
            return LockAcquisition.acquired(1);
        }

        @Override
        public boolean release(String lockKey, String owner) {
            return true;
        }

        @Override
        public boolean renew(String lockKey, String owner, long leaseMillis) {
            return renewed.get();
        }

        @Override
        public long getStorageTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
