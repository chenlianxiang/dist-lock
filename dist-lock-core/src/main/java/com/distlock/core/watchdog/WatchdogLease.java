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
    private volatile String lastFailureMessage;

    WatchdogLease(String lockKey) {
        this.lockKey = lockKey;
    }

    public String lockKey() { return lockKey; }
    public State state() { return state.get(); }
    public long renewSuccesses() { return renewSuccesses.get(); }
    public long renewFailures() { return renewFailures.get(); }
    public long delayedRuns() { return delayedRuns.get(); }
    public String lastFailureMessage() { return lastFailureMessage; }

    void renewed() {
        renewSuccesses.incrementAndGet();
        state.compareAndSet(State.DEGRADED, State.ACTIVE);
        lastFailureMessage = null;
    }

    void failed(Throwable failure) {
        renewFailures.incrementAndGet();
        lastFailureMessage = messageOf(failure);
        state.compareAndSet(State.ACTIVE, State.DEGRADED);
    }

    void delayed() { delayedRuns.incrementAndGet(); }

    void lost(Throwable failure) {
        lastFailureMessage = messageOf(failure);
        state.set(State.LOST);
    }

    void stopped() {
        state.updateAndGet(current -> current == State.LOST ? current : State.STOPPED);
    }

    private String messageOf(Throwable failure) {
        return failure == null ? null : failure.getMessage();
    }
}
