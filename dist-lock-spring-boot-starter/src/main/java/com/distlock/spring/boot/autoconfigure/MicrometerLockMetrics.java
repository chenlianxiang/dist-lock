package com.distlock.spring.boot.autoconfigure;

import com.distlock.core.metrics.LockMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

final class MicrometerLockMetrics implements LockMetrics {

    private final MeterRegistry registry;

    MicrometerLockMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordExecution(String strategy, String outcome, long durationNanos, int resourceCount) {
        Counter.builder("dist.lock.executions")
                .tag("strategy", strategy)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("dist.lock.execution.duration")
                .tag("strategy", strategy)
                .tag("outcome", outcome)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        registry.summary("dist.lock.resources", "strategy", strategy).record(resourceCount);
    }

    @Override
    public void recordRenewal(String strategy, String outcome, long delayNanos) {
        Counter.builder("dist.lock.watchdog.renewals")
                .tag("strategy", strategy)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        registry.timer("dist.lock.watchdog.delay", "strategy", strategy, "outcome", outcome)
                .record(delayNanos, TimeUnit.NANOSECONDS);
    }
}
