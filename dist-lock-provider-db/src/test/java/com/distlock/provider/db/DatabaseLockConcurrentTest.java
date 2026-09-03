package com.distlock.provider.db;

import com.distlock.core.api.DefaultDistributedLocker;
import com.distlock.core.api.DistributedLocker;
import com.distlock.core.exception.LockTimeoutException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseLockConcurrentTest {

    private static HikariDataSource dataSource;
    private static DatabaseLockStorageProvider storageProvider;
    private static DistributedLocker locker;

    // 自定义业务测试异常
    static class CustomBizException extends RuntimeException {
        private final int code;
        public CustomBizException(int code, String message) {
            super(message);
            this.code = code;
        }
        public int getCode() {
            return code;
        }
    }

    // 领域测试模型
    static class Order {
        private final String orderId;
        private final String customer;

        public Order(String orderId, String customer) {
            this.orderId = orderId;
            this.customer = customer;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getCustomer() {
            return customer;
        }
    }

    static class SkuStock {
        private final String skuCode;

        public SkuStock(String skuCode) {
            this.skuCode = skuCode;
        }

        public String getSkuCode() {
            return skuCode;
        }
    }

    @BeforeAll
    static void init() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:distlock_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(50);
        config.setMinimumIdle(10);
        dataSource = new HikariDataSource(config);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema/schema-h2.sql"));
        populator.execute(dataSource);

        storageProvider = new DatabaseLockStorageProvider(dataSource);
        locker = new DefaultDistributedLocker(storageProvider);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.execute("DELETE FROM dist_lock");
    }

    @Test
    @DisplayName("单对象直接加锁测试：加锁、执行、一步拿到结论")
    void testSingleObjectLockDirectConclusion() throws InterruptedException {
        int threadCount = 20;
        int perThreadRuns = 5;
        int expectedTotal = threadCount * perThreadRuns;
        AtomicInteger counter = new AtomicInteger(0);

        Order targetOrder = new Order("ORD-8888", "Alice");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < perThreadRuns; j++) {
                        Integer currentResult = locker.lock(targetOrder, Order::getOrderId, order -> {
                            int current = counter.get();
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException ignored) {
                            }
                            counter.set(current + 1);
                            return counter.get();
                        });
                        assertThat(currentResult).isNotNull();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(endLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(counter.get()).isEqualTo(expectedTotal);
    }

    @Test
    @DisplayName("多维度友好降级测试：默认友好消息、特制错误文案、自定义业务异常、函数式值降级")
    void testMultifacetedFriendlyDegradation() throws Exception {
        Order sharedOrder = new Order("ORD-1001", "Bob");
        CountDownLatch lockHoldingLatch = new CountDownLatch(1);
        CountDownLatch releaseSignalLatch = new CountDownLatch(1);

        // 线程 1 占用锁
        new Thread(() -> {
            locker.lock(sharedOrder, Order::getOrderId, order -> {
                lockHoldingLatch.countDown();
                try {
                    releaseSignalLatch.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });
        }).start();

        assertThat(lockHoldingLatch.await(2, TimeUnit.SECONDS)).isTrue();

        // 1. 默认兜底提示：抛出默认稳健的友好提示
        assertThatThrownBy(() -> {
            locker.lock(sharedOrder, Order::getOrderId, 100, TimeUnit.MILLISECONDS,
                    (String) null, order -> "FAIL");
        }).isInstanceOf(LockTimeoutException.class)
                .hasMessage(LockTimeoutException.DEFAULT_MESSAGE);

        // 2. 特制友好文案：抛出带有用户指定文案的异常
        assertThatThrownBy(() -> {
            locker.lock(sharedOrder, Order::getOrderId, 100, TimeUnit.MILLISECONDS,
                    "当前订单正在支付中，请勿重复操作", order -> "FAIL");
        }).isInstanceOf(LockTimeoutException.class)
                .hasMessage("当前订单正在支付中，请勿重复操作");

        // 3. 特制自定义业务异常：抛出用户特定业务异常
        assertThatThrownBy(() -> {
            locker.lock(sharedOrder, Order::getOrderId, 100, TimeUnit.MILLISECONDS,
                    () -> new CustomBizException(40901, "账户资金被锁定"), order -> "FAIL");
        }).isInstanceOf(CustomBizException.class)
                .hasMessage("账户资金被锁定");

        // 4. 函数式值降级：不抛异常，优雅返回降级对象
        String fallbackConclusion = locker.lock(
                sharedOrder,
                Order::getOrderId,
                100, TimeUnit.MILLISECONDS,
                order -> "NORMAL_SUCCESS",
                order -> "DEGRADED_VALUE"
        );
        assertThat(fallbackConclusion).isEqualTo("DEGRADED_VALUE");

        releaseSignalLatch.countDown();
    }

    @Test
    @DisplayName("集合批量加锁测试：自动字典序排序消除死锁")
    void testBatchLockDeadlockFree() throws InterruptedException {
        List<SkuStock> listA = Arrays.asList(new SkuStock("SKU-001"), new SkuStock("SKU-002"), new SkuStock("SKU-003"));
        List<SkuStock> listB = Arrays.asList(new SkuStock("SKU-003"), new SkuStock("SKU-002"), new SkuStock("SKU-001"));

        AtomicInteger totalProcessed = new AtomicInteger(0);

        int iterations = 10;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(iterations * 2);

        for (int i = 0; i < iterations; i++) {
            executor.submit(() -> {
                try {
                    Boolean res = locker.lock(listA, SkuStock::getSkuCode, items -> {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                        }
                        totalProcessed.addAndGet(items.size());
                        return true;
                    });
                    assertThat(res).isTrue();
                } finally {
                    latch.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    Boolean res = locker.lock(listB, SkuStock::getSkuCode, items -> {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                        }
                        totalProcessed.addAndGet(items.size());
                        return true;
                    });
                    assertThat(res).isTrue();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(totalProcessed.get()).isEqualTo(iterations * 2 * 3);
    }

    @Test
    @DisplayName("集合批量加锁部分失败自动原子回滚与特制报错")
    void testBatchLockRollbackAndCustomError() throws Exception {
        String occupiedKey = SkuStock.class.getName() + ":SKU-002";
        boolean preOccupied = storageProvider.tryAcquire(occupiedKey, "external-holder", 2000);
        assertThat(preOccupied).isTrue();

        List<SkuStock> batch = Arrays.asList(new SkuStock("SKU-001"), new SkuStock("SKU-002"), new SkuStock("SKU-003"));

        // 批量加锁失败抛出特制错误文案
        assertThatThrownBy(() -> {
            locker.lock(batch, SkuStock::getSkuCode, 100, TimeUnit.MILLISECONDS,
                    "购物车部分商品正在被抢购，请稍后结算", items -> "FAIL");
        }).isInstanceOf(LockTimeoutException.class)
                .hasMessage("购物车部分商品正在被抢购，请稍后结算");

        // 验证已获取的 SKU-001 已经被自动回滚释放
        String sku1Key = SkuStock.class.getName() + ":SKU-001";
        boolean canAcquireSku1 = storageProvider.tryAcquire(sku1Key, "new-owner", 1000);
        assertThat(canAcquireSku1).as("SKU-001 在批量失败后应已被逆序回滚释放").isTrue();
        storageProvider.release(sku1Key, "new-owner");

        storageProvider.release(occupiedKey, "external-holder");
    }
}
