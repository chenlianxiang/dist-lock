package com.distlock.example.model;

import java.math.BigDecimal;

/**
 * 用户账户模型（用于扣减余额/积分及超时降级场景）。
 */
public class UserAccountDTO {

    private final String userId;
    private final BigDecimal balance;

    public UserAccountDTO(String userId, BigDecimal balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public String getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    @Override
    public String toString() {
        return "UserAccountDTO{userId='" + userId + "', balance=" + balance + '}';
    }
}
