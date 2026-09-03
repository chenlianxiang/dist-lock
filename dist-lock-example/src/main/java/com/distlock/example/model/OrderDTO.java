package com.distlock.example.model;

import java.math.BigDecimal;

/**
 * 订单业务领域模型。
 */
public class OrderDTO {

    private final String orderId;
    private final String userId;
    private final BigDecimal amount;

    public OrderDTO(String orderId, String userId, BigDecimal amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public String toString() {
        return "OrderDTO{orderId='" + orderId + "', userId='" + userId + "', amount=" + amount + '}';
    }
}
