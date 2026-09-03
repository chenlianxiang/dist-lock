package com.distlock.core.watchdog;

import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.metrics.LockMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界多线程看门狗，记录续期成功、延迟、失败与所有权丢失状态。
 */
public class WatchdogCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WatchdogCoordinator.class);

    private final LockStorageProvider storageProvider;
    private final ScheduledExecutorService scheduler;
    private final String strategy;
    private final LockMetrics metrics;
    private final ConcurrentMap<String, RenewalTask> activeTasks = new ConcurrentHashMap<>();

    public WatchdogCoordinator(LockStorageProvider storageProvider) {
        this(storageProvider, Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                "UNKNOWN", LockMetrics.NOOP);
    }

    public WatchdogCoordinator(LockStorageProvider storageProvider, int threads) {
        this(storageProvider, threads, "UNKNOWN", LockMetrics.NOOP);
    }

    public WatchdogCoordinator(LockStorageProvider storageProvider,
                               int threads,
                               String strategy,
                               LockMetrics metrics) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider must not be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (threads <= 0) {
            throw new IllegalArgumentException("watchdog threads must be greater than 0");
        }
        AtomicLong sequence = new AtomicLong();
        this.scheduler = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "dist-lock-watchdog-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public WatchdogLease startRenew(String lockKey, String owner, long leaseMillis) {
        if (leaseMillis <= 0) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        String taskKey = buildTaskKey(lockKey, owner);
        long periodMillis = Math.max(1, leaseMillis / 3);
        long periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMillis);
        long leaseNanos = TimeUnit.MILLISECONDS.toNanos(leaseMillis);
        WatchdogLease lease = new WatchdogLease(lockKey);
        RenewalTask task = new RenewalTask(lease);
        AtomicLong lastSuccessNanos = new AtomicLong(System.nanoTime());
        AtomicLong expectedRunNanos = new AtomicLong(System.nanoTime() + periodNanos);

        RenewalTask old = activeTasks.put(taskKey, task);
        if (old != null) {
            old.lease.stopped();
            if (old.future != null) {
                old.future.cancel(true);
            }
        }

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            long expected = expectedRunNanos.getAndAdd(periodNanos);
            if (now - expected > periodNanos) {
                lease.delayed();
                metrics.recordRenewal(strategy, "delayed", now - expected);
            }
            try {
                if (storageProvider.renew(lockKey, owner, leaseMillis)) {
                    lastSuccessNanos.set(System.nanoTime());
                    lease.renewed();
                    metrics.recordRenewal(strategy, "success", Math.max(0, now - expected));
                } else {
                    lease.lost(new IllegalStateException("Lock is expired or owned by another acquisition"));
                    metrics.recordRenewal(strategy, "lost", Math.max(0, now - expected));
                    cancel(taskKey);
                }
            } catch (Throwable failure) {
                lease.failed(failure);
                metrics.recordRenewal(strategy, "error", Math.max(0, now - expected));
                if (System.nanoTime() - lastSuccessNanos.get() >= leaseNanos) {
                    lease.lost(failure);
                    cancel(taskKey);
                } else {
                    log.warn("Watchdog renewal error for lock [{}]; retrying before lease deadline", lockKey, failure);
                }
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);

        task.future = future;
        if (activeTasks.get(taskKey) != task) {
            future.cancel(false);
        }
        return lease;
    }

    public void stopRenew(String lockKey, String owner) {
        RenewalTask task = activeTasks.remove(buildTaskKey(lockKey, owner));
        if (task != null) {
            task.lease.stopped();
            if (task.future != null) {
                task.future.cancel(true);
            }
        }
    }

    public int activeTaskCount() {
        return activeTasks.size();
    }

    public void shutdown() {
        activeTasks.values().forEach(task -> task.lease.stopped());
        scheduler.shutdownNow();
        activeTasks.clear();
    }

    private void cancel(String taskKey) {
        RenewalTask task = activeTasks.remove(taskKey);
        if (task != null && task.future != null) {
            task.future.cancel(false);
        }
    }

    private String buildTaskKey(String lockKey, String owner) {
        return lockKey + "#" + owner;
    }

    private static final class RenewalTask {
        private final WatchdogLease lease;
        private volatile ScheduledFuture<?> future;

        private RenewalTask(WatchdogLease lease) {
            this.lease = lease;
        }
    }
}
