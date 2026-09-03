package com.distlock.example.service;

import com.distlock.core.api.DistributedLocker;
import com.distlock.example.model.OrderItemDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 库存服务：演示购物车/批量多商品扣减库存场景（自动死锁消除）。
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final DistributedLocker locker;

    public InventoryService(DistributedLocker locker) {
        this.locker = locker;
    }

    /**
     * 批量扣减库存。
     * <p>
     * 无论调用方以何种顺序传入商品列表，框架底层自动提取 SkuCode 并按字典序自然升序排列后顺序加锁，
     * 从根本上杜绝多用户同时购买相同商品产生的交叉死锁！
     */
    public boolean deductBatch(List<OrderItemDTO> items) {
        log.info("准备批量锁定商品库存，商品项数: {}", items.size());

        return locker.lock(items, OrderItemDTO::getSkuCode, list -> {
            log.info("--> 批量获取锁成功！已锁定 SKU 列表: {}", list);
            try {
                // 模拟批量扣减数据库库存
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            log.info("--> 批量扣减库存处理完毕！");
            return true;
        });
    }
}
