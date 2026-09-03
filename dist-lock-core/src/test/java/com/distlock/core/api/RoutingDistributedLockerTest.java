package com.distlock.core.api;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingDistributedLockerTest {

    @Test
    void routesDefaultAndExplicitStrategiesAtExecutionTime() {
        RecordingLocker database = new RecordingLocker();
        RecordingLocker redis = new RecordingLocker();
        RoutingDistributedLocker router = new RoutingDistributedLocker(
                Map.of("DATABASE", database, "REDIS", redis), "DATABASE");

        String defaultResult = router.lock("order", "1").call(() -> "DB");
        String redisResult = router.lock("order", "2")
                .strategy(LockStrategy.REDIS)
                .call(() -> "REDIS");

        assertThat(defaultResult).isEqualTo("DB");
        assertThat(redisResult).isEqualTo("REDIS");
        assertThat(database.executions).hasValue(1);
        assertThat(redis.executions).hasValue(1);
    }

    @Test
    void missingDefaultStrategyFailsFast() {
        RoutingDistributedLocker router = new RoutingDistributedLocker(
                Map.of("DATABASE", new RecordingLocker()), "REDIS");

        assertThatThrownBy(() -> router.lock("order", "2").call(() -> "RESULT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lock strategy [REDIS] is not available");
    }

    private static final class RecordingLocker implements DistributedLocker {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public LockOperation lock(String namespace, Object key) {
            return LockOperation.single(namespace, key, (snapshot, action) -> {
                executions.incrementAndGet();
                return LockOutcome.acquired(action.get());
            });
        }

        @Override
        public <T> LockOperation locks(String namespace,
                                       Collection<T> resources,
                                       Function<T, ?> keyExtractor) {
            return LockOperation.batch(namespace, resources, keyExtractor, (snapshot, action) -> {
                executions.incrementAndGet();
                return LockOutcome.acquired(action.get());
            });
        }
    }
}
