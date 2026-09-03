package com.distlock.spring.boot.autoconfigure;

import com.distlock.core.api.DefaultDistributedLocker;
import com.distlock.core.api.DistributedLocker;
import com.distlock.core.api.LockConfig;
import com.distlock.core.api.LockStrategy;
import com.distlock.core.api.RoutingDistributedLocker;
import com.distlock.core.fencing.FencingGuard;
import com.distlock.core.metrics.LockMetrics;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.watchdog.WatchdogCoordinator;
import com.distlock.provider.db.DatabaseLockStorageProvider;
import com.distlock.provider.db.JdbcFencingGuard;
import com.distlock.provider.redis.RedisLockStorageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁组件自动配置类。
 * <p>
 * 支持 DB 与 Redis 双底座条件装配，并构建支持动态策略选择的 {@link RoutingDistributedLocker} 主门面。
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@EnableConfigurationProperties(DistributedLockProperties.class)
@ConditionalOnClass(DistributedLocker.class)
public class DistributedLockAutoConfiguration {

    // =========================================================================
    // 1. 关系型数据库存储底座条件装配
    // =========================================================================
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({DataSource.class, DatabaseLockStorageProvider.class})
    @ConditionalOnBean(DataSource.class)
    public static class DatabaseLockConfiguration {

        @Bean(name = "databaseLockStorageProvider")
        @ConditionalOnMissingBean(name = "databaseLockStorageProvider")
        public LockStorageProvider databaseLockStorageProvider(DataSource dataSource,
                                                               DistributedLockProperties properties) {
            return new DatabaseLockStorageProvider(dataSource,
                    Duration.ofMillis(properties.getDatabaseOperationTimeout()));
        }

        @Bean(name = "databaseLockWatchdog", destroyMethod = "shutdown")
        @ConditionalOnMissingBean(name = "databaseLockWatchdog")
        public WatchdogCoordinator databaseLockWatchdog(
                @Qualifier("databaseLockStorageProvider") LockStorageProvider storageProvider,
                DistributedLockProperties properties,
                ObjectProvider<LockMetrics> metricsProvider) {
            return new WatchdogCoordinator(storageProvider, properties.getWatchdogThreads(),
                    LockStrategy.DATABASE.name(), metricsProvider.getIfAvailable(() -> LockMetrics.NOOP));
        }

        @Bean(name = "dbLocker")
        @ConditionalOnMissingBean(name = "dbLocker")
        public DefaultDistributedLocker dbLocker(
                @Qualifier("databaseLockStorageProvider") LockStorageProvider storageProvider,
                @Qualifier("databaseLockWatchdog") WatchdogCoordinator watchdog,
                @Qualifier("databaseFencingGuard") ObjectProvider<FencingGuard> fencingGuardProvider,
                DistributedLockProperties properties,
                ObjectProvider<LockMetrics> metricsProvider) {
            LockConfig config = createConfig(properties);
            return new DefaultDistributedLocker(storageProvider, watchdog, config, LockStrategy.DATABASE,
                    metricsProvider.getIfAvailable(() -> LockMetrics.NOOP),
                    fencingGuardProvider.getIfAvailable());
        }

        @Bean(name = "databaseFencingGuard")
        @ConditionalOnMissingBean(name = "databaseFencingGuard")
        @ConditionalOnProperty(prefix = "dist-lock", name = "fencing-mode",
                havingValue = "REQUIRED", matchIfMissing = true)
        public FencingGuard databaseFencingGuard(
                DataSource dataSource,
                ObjectProvider<PlatformTransactionManager> transactionManagers,
                DistributedLockProperties properties) {
            PlatformTransactionManager transactionManager = transactionManagers.getIfAvailable(
                    () -> new DataSourceTransactionManager(dataSource));
            return new JdbcFencingGuard(dataSource, transactionManager,
                    Duration.ofMillis(properties.getFencingTransactionTimeout()));
        }
    }

    // =========================================================================
    // 2. Redis 存储底座条件装配
    // =========================================================================
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({StringRedisTemplate.class, RedisLockStorageProvider.class})
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "dist-lock", name = "fencing-mode", havingValue = "DISABLED")
    public static class RedisLockConfiguration {

        @Bean(name = "redisLockStorageProvider")
        @ConditionalOnMissingBean(name = "redisLockStorageProvider")
        public LockStorageProvider redisLockStorageProvider(StringRedisTemplate redisTemplate) {
            return new RedisLockStorageProvider(redisTemplate);
        }

        @Bean(name = "redisLockWatchdog", destroyMethod = "shutdown")
        @ConditionalOnMissingBean(name = "redisLockWatchdog")
        public WatchdogCoordinator redisLockWatchdog(
                @Qualifier("redisLockStorageProvider") LockStorageProvider storageProvider,
                DistributedLockProperties properties,
                ObjectProvider<LockMetrics> metricsProvider) {
            return new WatchdogCoordinator(storageProvider, properties.getWatchdogThreads(),
                    LockStrategy.REDIS.name(), metricsProvider.getIfAvailable(() -> LockMetrics.NOOP));
        }

        @Bean(name = "redisLocker")
        @ConditionalOnMissingBean(name = "redisLocker")
        public DefaultDistributedLocker redisLocker(
                @Qualifier("redisLockStorageProvider") LockStorageProvider storageProvider,
                @Qualifier("redisLockWatchdog") WatchdogCoordinator watchdog,
                DistributedLockProperties properties,
                ObjectProvider<LockMetrics> metricsProvider) {
            LockConfig config = createConfig(properties);
            return new DefaultDistributedLocker(storageProvider, watchdog, config, LockStrategy.REDIS,
                    metricsProvider.getIfAvailable(() -> LockMetrics.NOOP), null);
        }
    }

    // =========================================================================
    // 3. 多引擎动态路由器 (@Primary 主门面)
    // =========================================================================
    @Bean(name = "distributedLocker")
    @Primary
    @ConditionalOnMissingBean(name = "distributedLocker")
    public DistributedLocker distributedLocker(
            ObjectProvider<DefaultDistributedLocker> lockersProvider,
            DistributedLockProperties properties) {

        Map<String, DistributedLocker> lockerMap = new HashMap<>();
        for (DefaultDistributedLocker locker : lockersProvider) {
            if (locker.getCurrentStrategy() != null) {
                lockerMap.put(locker.getCurrentStrategy().name().toUpperCase(), locker);
            }
        }

        String defaultStrategy = properties.getType() != null
                ? properties.getType().name()
                : LockStrategy.DATABASE.name();

        if (!lockerMap.containsKey(defaultStrategy)) {
            throw new IllegalStateException("Configured default lock strategy [" + defaultStrategy
                    + "] is not available. Registered strategies: " + lockerMap.keySet());
        }

        return new RoutingDistributedLocker(lockerMap, defaultStrategy);
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(LockMetrics.class)
    public LockMetrics distributedLockMetrics(MeterRegistry registry) {
        return new MicrometerLockMetrics(registry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    public static class ActuatorLockConfiguration {

        @Bean(name = "distributedLockHealthIndicator")
        @ConditionalOnMissingBean(name = "distributedLockHealthIndicator")
        public HealthIndicator distributedLockHealthIndicator(
                Map<String, LockStorageProvider> providers,
                Map<String, WatchdogCoordinator> watchdogs) {
            return new DistributedLockHealthIndicator(providers, watchdogs);
        }
    }

    private static LockConfig createConfig(DistributedLockProperties properties) {
        return LockConfig.of(
                properties.getDefaultWaitTimeout(), TimeUnit.MILLISECONDS,
                properties.getDefaultLeaseTime(), TimeUnit.MILLISECONDS,
                properties.isWatchdogEnabled()
        ).withFencingRequired(properties.getFencingMode()
                == DistributedLockProperties.FencingMode.REQUIRED);
    }
}
