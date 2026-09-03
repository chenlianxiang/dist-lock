package com.distlock.example.service;

import com.distlock.core.api.DistributedLocker;
import com.distlock.example.model.UserAccountDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * 账户服务：演示自定义业务异常工厂与函数式兜底降级。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final DistributedLocker locker;

    public AccountService(DistributedLocker locker) {
        this.locker = locker;
    }

    /**
     * 方式 A：特制业务异常工厂（与全局 @ExceptionHandler 配合）
     */
    public String deductWithCustomException(UserAccountDTO account, BigDecimal amount) {
        return locker.lock(
                account,
                UserAccountDTO::getUserId,
                500, TimeUnit.MILLISECONDS,
                () -> new IllegalStateException("用户账户资金正在结算，请稍后"), // 特制业务异常
                acc -> {
                    log.info("--> 扣除用户 [{}] 资金: {}", acc.getUserId(), amount);
                    return "DEDUCT_SUCCESS";
                }
        );
    }

    /**
     * 方式 B：函数式兜底降级（不抛异常，返回优雅结论）
     */
    public String deductWithFallback(UserAccountDTO account, BigDecimal amount) {
        return locker.lock(
                account,
                UserAccountDTO::getUserId,
                500, TimeUnit.MILLISECONDS,
                acc -> {
                    log.info("--> 正常扣除用户 [{}] 资金: {}", acc.getUserId(), amount);
                    return "DEDUCT_SUCCESS";
                },
                acc -> {
                    log.warn("--> 拿锁超时，转入降级流程！");
                    return "SYSTEM_BUSY_FALLBACK";
                }
        );
    }
}
