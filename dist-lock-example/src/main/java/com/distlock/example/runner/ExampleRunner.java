package com.distlock.example.runner;

import com.distlock.core.api.DistributedLocker;
import com.distlock.core.api.LockStrategy;
import com.distlock.example.model.OrderDTO;
import com.distlock.example.model.OrderItemDTO;
import com.distlock.example.model.UserAccountDTO;
import com.distlock.example.service.AccountService;
import com.distlock.example.service.InventoryService;
import com.distlock.example.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 示例启动演示运行器：启动时自动演示多维度友好提示与自主选择锁策略。
 */
@Component
public class ExampleRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ExampleRunner.class);

    private final DistributedLocker locker;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final AccountService accountService;

    public ExampleRunner(DistributedLocker locker,
                         OrderService orderService,
                         InventoryService inventoryService,
                         AccountService accountService) {
        this.locker = locker;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.accountService = accountService;
    }

    @Override
    public void run(String... args) {
        log.info("=================================================================");
        log.info(">>> 开始演示分布式锁通用组件的友好提示、多维度降级与自主策略实战 <<<");
        log.info("=================================================================");

        // --- 场景 1：默认策略（走 DB）加锁 ---
        log.info("\n[案例 1]：默认策略 -> 订单支付（支持特制友好提示）");
        OrderDTO order = new OrderDTO("ORD-20260903001", "U_888", new BigDecimal("199.00"));
        String orderConclusion = orderService.payOrder(order);
        log.info(">>> 案例 1 结论: {}\n", orderConclusion);

        // --- 场景 2：集合批量加锁（多商品库存防死锁扣减） ---
        log.info("[案例 2]：集合批量对象加锁 -> 购物车商品批量扣库存（自动排序防死锁）");
        List<OrderItemDTO> items = Arrays.asList(
                new OrderItemDTO("SKU_IPHONE_16", "iPhone 16 Pro", 1),
                new OrderItemDTO("SKU_AIRPODS_PRO", "AirPods Pro 2", 2),
                new OrderItemDTO("SKU_APPLE_WATCH", "Apple Watch S10", 1)
        );
        boolean batchConclusion = inventoryService.deductBatch(items);
        log.info(">>> 案例 2 结论: 全部成功 = {}\n", batchConclusion);

        // --- 场景 3：函数式值降级兜底 ---
        log.info("[案例 3]：单业务对象加锁 -> 账户余额扣减（函数式兜底降级）");
        UserAccountDTO account = new UserAccountDTO("U_888", new BigDecimal("5000.00"));
        String fallbackConclusion = accountService.deductWithFallback(account, new BigDecimal("199.00"));
        log.info(">>> 案例 3 结论: {}\n", fallbackConclusion);

        // --- 场景 4：特制业务异常工厂 ---
        log.info("[案例 4]：特制业务异常演示");
        String customExConclusion = accountService.deductWithCustomException(account, new BigDecimal("100.00"));
        log.info(">>> 案例 4 结论: {}\n", customExConclusion);

        // --- 场景 5：自主选择锁策略 (动态切换 use(LockStrategy)) ---
        log.info("[案例 5]：自主选择锁策略 -> locker.use(LockStrategy.DATABASE)");
        OrderDTO vipOrder = new OrderDTO("ORD-VIP-001", "U_999", new BigDecimal("8888.00"));
        String vipResult = locker.use(LockStrategy.DATABASE).lock(vipOrder, OrderDTO::getOrderId, o -> "VIP_ORDER_PAID_" + o.getOrderId());
        log.info(">>> 案例 5 结论 (自主指定 DATABASE 策略): {}\n", vipResult);

        log.info("=================================================================");
        log.info(">>> 所有实战案例演示完毕！<<<");
        log.info("=================================================================");
    }
}
