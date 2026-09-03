package com.distlock.spring.boot.autoconfigure;

import com.distlock.core.api.DefaultDistributedLocker;
import com.distlock.core.api.DistributedLocker;
import com.distlock.core.api.LockConfig;
import com.distlock.core.api.LockStrategy;
import com.distlock.core.api.RoutingDistributedLocker;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.watchdog.WatchdogCoordinator;
import com.distlock.provider.db.DatabaseLockStorageProvider;
import com.distlock.provider.redis.RedisLockStorageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
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
        public LockStorageProvider databaseLockStorageProvider(DataSource dataSource) {
            return new DatabaseLockStorageProvider(dataSource);
        }

        @Bean(name = "dbLocker")
        @ConditionalOnMissingBean(name = "dbLocker")
        public DefaultDistributedLocker dbLocker(@Qualifier("databaseLockStorageProvider") LockStorageProvider storageProvider,
                                                 DistributedLockProperties properties) {
            LockConfig config = createConfig(properties);
            return new DefaultDistributedLocker(storageProvider, new WatchdogCoordinator(storageProvider), config, LockStrategy.DATABASE);
        }
    }

    // =========================================================================
    // 2. Redis 存储底座条件装配
    // =========================================================================
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({StringRedisTemplate.class, RedisLockStorageProvider.class})
    @ConditionalOnBean(StringRedisTemplate.class)
    public static class RedisLockConfiguration {

        @Bean(name = "redisLockStorageProvider")
        @ConditionalOnMissingBean(name = "redisLockStorageProvider")
        public LockStorageProvider redisLockStorageProvider(StringRedisTemplate redisTemplate) {
            return new RedisLockStorageProvider(redisTemplate);
        }

        @Bean(name = "redisLocker")
        @ConditionalOnMissingBean(name = "redisLocker")
        public DefaultDistributedLocker redisLocker(@Qualifier("redisLockStorageProvider") LockStorageProvider storageProvider,
                                                    DistributedLockProperties properties) {
            LockConfig config = createConfig(properties);
            return new DefaultDistributedLocker(storageProvider, new WatchdogCoordinator(storageProvider), config, LockStrategy.REDIS);
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

        return new RoutingDistributedLocker(lockerMap, defaultStrategy);
    }

    private static LockConfig createConfig(DistributedLockProperties properties) {
        return LockConfig.of(
                properties.getDefaultWaitTimeout(), TimeUnit.MILLISECONDS,
                properties.getDefaultLeaseTime(), TimeUnit.MILLISECONDS,
                properties.isWatchdogEnabled()
        );
    }
}
