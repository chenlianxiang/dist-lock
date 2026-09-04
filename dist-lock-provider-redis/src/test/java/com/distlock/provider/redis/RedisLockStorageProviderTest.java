package com.distlock.provider.redis;

import com.distlock.core.exception.LockStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisLockStorageProviderTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisLockStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RedisLockStorageProvider(redisTemplate);
    }

    @Test
    @DisplayName("Redis tryAcquire: Lua 原子获取并返回 fencing token")
    void testTryAcquireSuccess() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("node-1"), eq("30000")))
                .thenReturn(7L);

        var acquired = provider.tryAcquire("order:1001", "node-1", 30000);

        assertThat(acquired.acquired()).isTrue();
        assertThat(acquired.fencingToken()).isEqualTo(7);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("node-1"), eq("30000"));
    }

    @Test
    @DisplayName("Redis tryAcquire: 键已存在争抢失败")
    void testTryAcquireFailure() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("node-1"), eq("30000")))
                .thenReturn(0L);

        var acquired = provider.tryAcquire("order:1001", "node-1", 30000);

        assertThat(acquired.acquired()).isFalse();
    }

    @Test
    @DisplayName("Redis release: 执行原子 Lua 脚本释放锁")
    void testReleaseSuccess() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("node-1")))
                .thenReturn(1L);

        boolean released = provider.release("order:1001", "node-1");

        assertThat(released).isTrue();
    }

    @Test
    @DisplayName("Redis renew: 执行原子 Lua 脚本续期租约")
    void testRenewSuccess() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("node-1"), eq("30000")))
                .thenReturn(1L);

        boolean renewed = provider.renew("order:1001", "node-1", 30000);

        assertThat(renewed).isTrue();
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("node-1"), eq("30000"));
    }

    @Test
    @DisplayName("Redis 故障必须作为存储异常传播，不能伪装成锁竞争")
    void testStorageFailureIsPropagated() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("node-1"), eq("30000")))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> provider.tryAcquire("order:1001", "node-1", 30000))
                .isInstanceOf(LockStorageException.class)
                .hasMessageContaining("acquire");
    }
}
