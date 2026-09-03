package com.distlock.provider.redis;

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
}
