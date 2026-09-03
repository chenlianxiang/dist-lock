package com.distlock.spring.boot.autoconfigure;

import com.distlock.core.api.DistributedLocker;
import com.distlock.core.api.LockStrategy;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.metrics.LockMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockAutoConfigurationTest {

    private static final class StarterTestLock {}

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DistributedLockAutoConfiguration.class
            ))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:starter_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "dist-lock.default-wait-timeout=5000",
                    "dist-lock.default-lease-time=20000"
            );

    @Test
    void testAutoConfigurationSuccess() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("databaseLockStorageProvider");
            assertThat(context).hasBean("dbLocker");
            assertThat(context).hasBean("distributedLocker");
            assertThat(context).hasSingleBean(LockMetrics.class);
            assertThat(context).hasBean("distributedLockHealthIndicator");

            DistributedLocker primaryLocker = context.getBean(DistributedLocker.class);
            assertThat(primaryLocker).isNotNull();

            // 验证链式操作可以配置策略
            var operation = primaryLocker.lock("1", Function.identity())
                    .scope(StarterTestLock.class)
                    .strategy(LockStrategy.DATABASE);
            assertThat(operation).isNotNull();

            DistributedLockProperties props = context.getBean(DistributedLockProperties.class);
            assertThat(props.getDefaultWaitTimeout()).isEqualTo(5000);
            assertThat(props.getDefaultLeaseTime()).isEqualTo(20000);
            HealthIndicator health = context.getBean("distributedLockHealthIndicator", HealthIndicator.class);
            assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        });
    }

    @Test
    void startupFailsWhenConfiguredDefaultProviderIsMissing() {
        contextRunner
                .withPropertyValues("dist-lock.type=REDIS")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Configured default lock strategy [REDIS] is not available. "
                                    + "Registered strategies: [DATABASE]");
                });
    }

    @Test
    void actuatorRemainsTrulyOptional() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("distributedLockHealthIndicator");
                    assertThat(context).hasBean("distributedLocker");
                });
    }
}
