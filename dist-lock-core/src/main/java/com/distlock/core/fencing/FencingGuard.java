package com.distlock.core.fencing;

import com.distlock.core.api.LockHandle;

import java.util.function.Supplier;

/**
 * 在业务闭包执行前原子声明 fencing token，并让闭包加入同一提交边界。
 * 实现必须先拒绝旧 token，再执行业务；不能用非原子的“先检查、后执行”替代。
 */
public interface FencingGuard {

    <R> R execute(LockHandle handle, Supplier<R> action);
}
