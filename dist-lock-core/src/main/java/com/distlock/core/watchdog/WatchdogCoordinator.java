package com.distlock.core.watchdog;

import com.distlock.core.spi.LockStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * 看门狗（Watchdog）租约自动续约协调器。
 * <p>
 * 职责：
 * 1. 当业务执行时间较长时，按周期 (leaseTime / 3) 自动调用底层 SPI 的 renew 接口进行租约延期；
 * 2. 避免锁因预估时间不足而提前失效导致并发穿透；
 * 3. 当业务结束（释放锁）、发生异常或持有线程退出时，停止续约任务；
 * 4. 守护线程调度，不阻塞 JVM 正常退出。
 */
public class WatchdogCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WatchdogCoordinator.class);

    private final LockStorageProvider storageProvider;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public WatchdogCoordinator(LockStorageProvider storageProvider) {
        this.storageProvider = storageProvider;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dist-lock-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 注册并启动一个租约自动续期任务。
     *
     * @param lockKey     锁资源 Key
     * @param owner       持有者全局签名
     * @param leaseMillis 租约时长（毫秒）
     */
    public void startRenew(String lockKey, String owner, long leaseMillis) {
        String taskKey = buildTaskKey(lockKey, owner);
        long period = Math.max(100, leaseMillis / 3);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                log.debug("Watchdog attempting renew for lock [{}], owner: [{}]", lockKey, owner);
                boolean renewed = storageProvider.renew(lockKey, owner, leaseMillis);
                if (!renewed) {
                    log.warn("Watchdog renewal failed for lock [{}] (lock lost or expired), canceling renew task", lockKey);
                    stopRenew(lockKey, owner);
                } else {
                    log.debug("Watchdog successfully renewed lock [{}] for next {} ms", lockKey, leaseMillis);
                }
            } catch (Throwable t) {
                log.error("Watchdog encountered error during renew for lock [{}]", lockKey, t);
            }
        }, period, period, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> old = activeTasks.put(taskKey, future);
        if (old != null) {
            old.cancel(true);
        }
    }

    /**
     * 停止并注销租约续期任务。
     *
     * @param lockKey 锁资源 Key
     * @param owner   持有者全局签名
     */
    public void stopRenew(String lockKey, String owner) {
        String taskKey = buildTaskKey(lockKey, owner);
        ScheduledFuture<?> future = activeTasks.remove(taskKey);
        if (future != null) {
            future.cancel(true);
            log.debug("Watchdog stopped renew for lock [{}]", lockKey);
        }
    }

    private String buildTaskKey(String lockKey, String owner) {
        return lockKey + "#" + owner;
    }

    /**
     * 优雅关闭看门狗调度器。
     */
    public void shutdown() {
        scheduler.shutdownNow();
        activeTasks.clear();
    }
}
