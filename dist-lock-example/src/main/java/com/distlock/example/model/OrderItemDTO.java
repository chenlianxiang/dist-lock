package com.distlock.example.model;

/**
 * 购物车/订单明细商品（用于批量扣减库存场景）。
 */
public class OrderItemDTO {

    private final String skuCode;
    private final String title;
    private final int quantity;

    public OrderItemDTO(String skuCode, String title, int quantity) {
        this.skuCode = skuCode;
        this.title = title;
        this.quantity = quantity;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public String getTitle() {
        return title;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public String toString() {
        return "OrderItemDTO{skuCode='" + skuCode + "', title='" + title + "', quantity=" + quantity + '}';
    }
}
