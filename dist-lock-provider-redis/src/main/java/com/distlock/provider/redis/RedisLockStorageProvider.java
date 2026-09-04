package com.distlock.provider.redis;

import com.distlock.core.spi.LockAcquisition;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.exception.LockStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 基于 Redis 的分布式锁存储实现。
 * <p>
 * 采用 Lua 原子获取与 fencing token，并通过脚本保障持有者原子释放与看门狗原子续期，
 * 适用于超高 QPS、毫秒级/纳秒级争抢的极速业务场景（如秒杀、高频防重）。
 */
public final class RedisLockStorageProvider implements LockStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisLockStorageProvider.class);

    private final StringRedisTemplate redisTemplate;

    private static final RedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 0 then " +
                    "local token = redis.call('incr', KEYS[2]); " +
                    "redis.call('psetex', KEYS[1], ARGV[2], ARGV[1]); " +
                    "return token; " +
                    "else return 0; end",
            Long.class
    );

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
    public LockAcquisition tryAcquire(String lockKey, String owner, long leaseMillis) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        try {
            Long token = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    java.util.List.of(redisKey(lockKey, "lease"), redisKey(lockKey, "fence")),
                    owner,
                    String.valueOf(leaseMillis)
            );
            return token != null && token > 0
                    ? LockAcquisition.acquired(token)
                    : LockAcquisition.contended();
        } catch (RuntimeException t) {
            log.error("Redis error while acquiring lock [{}] for owner [{}]", lockKey, owner, t);
            throw new LockStorageException("acquire", lockKey, t);
        }
    }

    @Override
    public boolean release(String lockKey, String owner) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        try {
            Long result = redisTemplate.execute(
                    RELEASE_SCRIPT,
                    Collections.singletonList(redisKey(lockKey, "lease")),
                    owner
            );
            return result != null && result > 0;
        } catch (RuntimeException t) {
            log.error("Redis error while releasing lock [{}] for owner [{}]", lockKey, owner, t);
            throw new LockStorageException("release", lockKey, t);
        }
    }

    @Override
    public boolean renew(String lockKey, String owner, long leaseMillis) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        try {
            Long result = redisTemplate.execute(
                    RENEW_SCRIPT,
                    Collections.singletonList(redisKey(lockKey, "lease")),
                    owner,
                    String.valueOf(leaseMillis)
            );
            return result != null && result > 0;
        } catch (RuntimeException t) {
            log.error("Redis error while renewing lock [{}] for owner [{}]", lockKey, owner, t);
            throw new LockStorageException("renew", lockKey, t);
        }
    }

    @Override
    public long getStorageTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void validateConnectivity() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if (pong == null || !"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("Redis PING returned [" + pong + "]");
            }
        } catch (RuntimeException exception) {
            throw new LockStorageException("health", "<redis>", exception);
        }
    }

    private static String redisKey(String lockKey, String suffix) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(lockKey.getBytes(StandardCharsets.UTF_8));
            return "dist-lock:{" + HexFormat.of().formatHex(digest) + "}:" + suffix;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
