package com.distlock.core.metrics;

/**
 * 核心层可观测事件出口；默认无依赖，Starter 可桥接到 Micrometer。
 */
public interface LockMetrics {

    LockMetrics NOOP = new LockMetrics() {};

    default void recordExecution(String strategy, String outcome, long durationNanos, int resourceCount) {
    }

    default void recordRenewal(String strategy, String outcome, long delayNanos) {
    }
}
