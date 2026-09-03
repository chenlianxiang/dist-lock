package com.distlock.core.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutingDistributedLockerTest {

    @Mock
    private DistributedLocker dbLocker;

    @Mock
    private DistributedLocker redisLocker;

    static class Order {
        private final String id;
        public Order(String id) { this.id = id; }
        public String getId() { return id; }
    }

    @Test
    @DisplayName("路由分发测试：默认走 DATABASE，自主 use(REDIS) 走 REDIS")
    void testRoutingDispatch() {
        Map<String, DistributedLocker> map = new HashMap<>();
        map.put("DATABASE", dbLocker);
        map.put("REDIS", redisLocker);

        RoutingDistributedLocker router = new RoutingDistributedLocker(map, "DATABASE");

        Order order = new Order("ORD-001");
        Function<Order, String> action = o -> "RESULT";

        // 1. 默认调用 lock 走 DATABASE
        router.lock(order, Order::getId, action);
        verify(dbLocker).lock(eq(order), any(), eq(action));
        verifyNoInteractions(redisLocker);

        // 2. 自主选择 use(LockStrategy.REDIS)
        DistributedLocker chosenRedis = router.use(LockStrategy.REDIS);
        assertThat(chosenRedis).isSameAs(redisLocker);

        // 3. 动态扩展未支持的策略抛出友好异常
        assertThatThrownBy(() -> router.use(LockStrategy.of("ETCD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported lock strategy [ETCD]");
    }
}
