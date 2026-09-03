package com.distlock.provider.redis;

import com.distlock.core.api.DefaultDistributedLocker;
import com.distlock.core.api.LockConfig;
import com.distlock.core.api.LockStrategy;
import com.distlock.core.watchdog.WatchdogCoordinator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisLockStorageProviderIT {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisLockStorageProvider provider;

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        provider = new RedisLockStorageProvider(template);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void luaAcquisitionIsExclusiveAndFencingTokenIsMonotonic() {
        var first = provider.tryAcquire("redis-fence", "owner-1", 5_000);
        var contended = provider.tryAcquire("redis-fence", "owner-2", 5_000);

        assertThat(first.acquired()).isTrue();
        assertThat(contended.acquired()).isFalse();
        assertThat(provider.renew("redis-fence", "owner-1", 5_000)).isTrue();
        assertThat(provider.release("redis-fence", "owner-1")).isTrue();

        var second = provider.tryAcquire("redis-fence", "owner-2", 5_000);
        assertThat(second.acquired()).isTrue();
        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        provider.release("redis-fence", "owner-2");
    }

    @Test
    void healthCheckUsesRealRedisConnection() {
        provider.validateConnectivity();
    }

    @Test
    void expiredLeaseCanBeAcquiredWithANewerFencingToken() throws Exception {
        var first = provider.tryAcquire("redis-expiry", "owner-1", 100);
        assertThat(first.acquired()).isTrue();

        Thread.sleep(250);

        var second = provider.tryAcquire("redis-expiry", "owner-2", 1_000);
        assertThat(second.acquired()).isTrue();
        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        provider.release("redis-expiry", "owner-2");
    }

    @Test
    void watchdogKeepsLongBusinessExclusiveBeyondInitialLease() throws Exception {
        WatchdogCoordinator coordinator = new WatchdogCoordinator(provider, 2,
                LockStrategy.REDIS.name(), com.distlock.core.metrics.LockMetrics.NOOP);
        DefaultDistributedLocker locker = new DefaultDistributedLocker(
                provider,
                coordinator,
                LockConfig.of(20, TimeUnit.MILLISECONDS, 120, TimeUnit.MILLISECONDS, true),
                LockStrategy.REDIS
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch businessStarted = new CountDownLatch(1);
        String resource = "long-business";
        String logicalKey = "dist-lock:v1:java.lang.String:java.lang.String:" + resource;

        try {
            Future<Boolean> holder = executor.submit(() -> locker
                    .lock(resource, Function.identity())
                    .call(() -> {
                        businessStarted.countDown();
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("business thread interrupted", exception);
                        }
                        return true;
                    }));

            assertThat(businessStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);
            assertThat(provider.tryAcquire(logicalKey, "competitor", 1_000).acquired()).isFalse();
            assertThat(holder.get(2, TimeUnit.SECONDS)).isTrue();

            var afterRelease = provider.tryAcquire(logicalKey, "competitor", 1_000);
            assertThat(afterRelease.acquired()).isTrue();
            provider.release(logicalKey, "competitor");
        } finally {
            coordinator.shutdown();
            executor.shutdownNow();
        }
    }
}
