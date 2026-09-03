package com.distlock.core.api;

import org.junit.jupiter.api.Test;

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

        String defaultResult = router.lock("1", Function.identity()).call(() -> "DB");
        String redisResult = router.lock("2", Function.identity())
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

        assertThatThrownBy(() -> router.lock("2", Function.identity()).call(() -> "RESULT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lock strategy [REDIS] is not available");
    }

    private static final class RecordingLocker implements DistributedLocker {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public <T> LockOperation lock(Object resourceOrResources,
                                      Function<T, ?> keyExtractor) {
            return LockOperation.create(resourceOrResources, keyExtractor, (snapshot, action) -> {
                executions.incrementAndGet();
                return LockOutcome.acquired(action.get());
            });
        }
    }
}
