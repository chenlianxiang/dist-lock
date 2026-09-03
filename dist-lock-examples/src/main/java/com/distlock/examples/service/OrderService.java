package com.distlock.example.service;

import com.distlock.core.api.DistributedLocker;
import com.distlock.example.model.OrderDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订单服务：演示单对象加锁与特制友好提示。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final DistributedLocker locker;

    public OrderService(DistributedLocker locker) {
        this.locker = locker;
    }

    /**
     * 支付订单：使用自定义特制错误文案防重。
     * 若短时间内并发重复点击，直接抛出用户特制友好异常：“当前订单正在支付中，请勿重复操作”。
     */
    public String payOrder(OrderDTO order) {
        return locker.lock("order-payment", order.getOrderId())
                .tryCall(() -> {
                    log.info("--> 获得订单锁，正在执行支付扣款: {}", order.getOrderId());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                    return "SUCCESS_PAID_" + order.getOrderId();
                })
                .orElseThrow(() -> new IllegalStateException("当前订单正在支付中，请勿重复操作"));
    }
}
