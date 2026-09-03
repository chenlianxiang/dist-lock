package com.distlock.core.backoff;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 带随机抖动（Jitter）的自适应指数退避等待器。
 * <p>
 * 用于在多节点自旋争抢锁时，错开各节点的重试时刻，避免“惊群效应”打垮底层存储系统。
 */
public class AdaptiveBackoff {

    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final double multiplier;
    private final double jitter;

    private long currentDelay;

    public AdaptiveBackoff() {
        this(20, 300, 1.5, 0.5);
    }

    public AdaptiveBackoff(long initialDelayMillis, long maxDelayMillis, double multiplier, double jitter) {
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        this.jitter = jitter;
        this.currentDelay = initialDelayMillis;
    }

    /**
     * 计算并执行下一次退避休眠。
     *
     * @param remainingMillis 剩余最大允许等待时间（毫秒）
     * @throws InterruptedException 若当前线程被中断
     */
    public void backoff(long remainingMillis) throws InterruptedException {
        long sleepTime = nextSleepMillis();
        // 不能超过剩余超时预算
        sleepTime = Math.min(sleepTime, Math.max(1, remainingMillis));
        TimeUnit.MILLISECONDS.sleep(sleepTime);
    }

    /**
     * 计算下一次休眠时长（包含抖动）。
     */
    private long nextSleepMillis() {
        double randomFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitter;
        long sleepTime = (long) (currentDelay * randomFactor);
        sleepTime = Math.max(1, Math.min(sleepTime, maxDelayMillis));

        // 指数增加下一次的基础延时
        currentDelay = (long) Math.min(maxDelayMillis, currentDelay * multiplier);
        return sleepTime;
    }

    /**
     * 重置退避状态。
     */
    public void reset() {
        this.currentDelay = initialDelayMillis;
    }
}
