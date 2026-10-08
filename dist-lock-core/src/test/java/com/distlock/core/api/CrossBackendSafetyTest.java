package com.distlock.core.api;

import com.distlock.core.spi.LockStorageProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossBackendSafetyTest {
    record Resource(String id) {}

    @Test
    void rejectsConflictingBackendsForSameResourceWithinJvm() {
        LockStorageProvider provider = new LockStorageProvider() {
            @Override public boolean tryAcquire(String key, String owner, long lease) { return true; }
            @Override public boolean release(String key, String owner) { return true; }
            @Override public boolean renew(String key, String owner, long lease) { return true; }
            @Override public long getStorageTimeMillis() { return System.currentTimeMillis(); }
        };
        var db = new DefaultDistributedLocker(provider, LockStrategy.DATABASE);
        var redis = new DefaultDistributedLocker(provider, LockStrategy.REDIS);
        Resource resource = new Resource("cross-backend-safety-test");
        db.lock(resource, Resource::id).run(() -> {});
        assertThatThrownBy(() -> redis.lock(resource, Resource::id).run(() -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting lock backend");
    }
}
