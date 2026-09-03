package com.distlock.provider.redis;

import com.distlock.core.spi.LockStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

/**
 * 基于 Redis 的分布式锁存储实现。
 * <p>
 * 采用原生原子 SET NX PX 指令与经典 Lua 脚本保障持有者原子释放与看门狗原子续期，
 * 适用于超高 QPS、毫秒级/纳秒级争抢的极速业务场景（如秒杀、高频防重）。
 */
public class RedisLockStorageProvider implements LockStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisLockStorageProvider.class);

    private final StringRedisTemplate redisTemplate;

    // 原子释放锁 Lua 脚本
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "   return redis.call('del', KEYS[1]) " +
                    "else " +
                    "   return 0 " +
                    "end",
            Long.class
    );

    // 原子看门狗续期 Lua 脚本
    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "   return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                    "else " +
                    "   return 0 " +
                    "end",
            Long.class
    );

    public RedisLockStorageProvider(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate must not be null");
    }

    @Override
    public boolean tryAcquire(String lockKey, String owner, long leaseMillis) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    owner,
                    Duration.ofMillis(leaseMillis)
            );
            return Boolean.TRUE.equals(success);
        } catch (Throwable t) {
            log.error("Redis error while acquiring lock [{}] for owner [{}]", lockKey, owner, t);
            return false;
        }
    }

    @Override
    public boolean release(String lockKey, String owner) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        try {
            Long result = redisTemplate.execute(
                    RELEASE_SCRIPT,
                    Collections.singletonList(lockKey),
                    owner
            );
            return result != null && result > 0;
        } catch (Throwable t) {
            log.error("Redis error while releasing lock [{}] for owner [{}]", lockKey, owner, t);
            return false;
        }
    }

    @Override
    public boolean renew(String lockKey, String owner, long leaseMillis) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        try {
            Long result = redisTemplate.execute(
                    RENEW_SCRIPT,
                    Collections.singletonList(lockKey),
                    String.valueOf(leaseMillis)
            );
            return result != null && result > 0;
        } catch (Throwable t) {
            log.error("Redis error while renewing lock [{}] for owner [{}]", lockKey, owner, t);
            return false;
        }
    }

    @Override
    public long getStorageTimeMillis() {
        return System.currentTimeMillis();
    }
}
