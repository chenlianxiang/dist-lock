package com.distlock.core.watchdog;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单把锁的看门狗状态快照。
 */
public final class WatchdogLease {

    public enum State { ACTIVE, DEGRADED, LOST, STOPPED }

    private final String lockKey;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private final AtomicLong renewSuccesses = new AtomicLong();
    private final AtomicLong renewFailures = new AtomicLong();
    private final AtomicLong delayedRuns = new AtomicLong();
    private volatile Throwable lastFailure;

    WatchdogLease(String lockKey) {
        this.lockKey = lockKey;
    }

    public String lockKey() { return lockKey; }
    public State state() { return state.get(); }
    public long renewSuccesses() { return renewSuccesses.get(); }
    public long renewFailures() { return renewFailures.get(); }
    public long delayedRuns() { return delayedRuns.get(); }
    public Throwable lastFailure() { return lastFailure; }

    void renewed() {
        renewSuccesses.incrementAndGet();
        state.compareAndSet(State.DEGRADED, State.ACTIVE);
        lastFailure = null;
    }

    void failed(Throwable failure) {
        renewFailures.incrementAndGet();
        lastFailure = failure;
        state.compareAndSet(State.ACTIVE, State.DEGRADED);
    }

    void delayed() { delayedRuns.incrementAndGet(); }

    void lost(Throwable failure) {
        lastFailure = failure;
        state.set(State.LOST);
    }

    void stopped() {
        state.updateAndGet(current -> current == State.LOST ? current : State.STOPPED);
    }
}
