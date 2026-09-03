package com.distlock.core.api;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 分布式锁核心统一门面。
 * <p>
 * 支持多维度的友好降级与特制报错：
 * <ul>
 *   <li>默认兜底提示：失败时默认抛出固定稳健的友好异常（如“系统繁忙，当前业务正在处理中，请稍候重试”）；</li>
 *   <li>特制错误文本：支持用户直接传入自定义错误文案（如“当前订单正在处理中，请勿重复提交”）；</li>
 *   <li>特制业务异常：支持传入自定义异常工厂 {@link Supplier}（如抛出业务特定的 BusinessException）；</li>
 *   <li>函数式值降级：支持传入 fallback 函数，失败时不抛异常直接返回兜底结论。</li>
 * </ul>
 */
public interface DistributedLocker {

    /**
     * 动态切换至指定的锁策略（如 LockStrategy.REDIS、LockStrategy.DATABASE）。
     *
     * @param strategy 锁策略
     * @return 绑定该策略的分布式锁操作门面
     */
    DistributedLocker use(LockStrategy strategy);

    /**
     * 根据策略名称字符串动态切换锁策略（如 "REDIS"、"DATABASE"）。
     */
    default DistributedLocker use(String strategyName) {
        return use(LockStrategy.of(strategyName));
    }

    // =========================================================================
    // 1. 单对象直接加锁执行
    // =========================================================================

    /**
     * 单对象直接加锁执行（失败抛出默认固定友好提示异常）。
     */
    <T, R> R lock(T data, Function<T, ?> keyExtractor, Function<T, R> action);

    <T> void lock(T data, Function<T, ?> keyExtractor, Consumer<T> action);

    /**
     * 单对象直接加锁执行，支持用户传入特制的友好错误提示文案。
     */
    <T, R> R lock(T data, Function<T, ?> keyExtractor, String errorMessage, Function<T, R> action);

    <T> void lock(T data, Function<T, ?> keyExtractor, String errorMessage, Consumer<T> action);

    /**
     * 单对象直接加锁执行，支持用户传入特制的自定义业务异常工厂。
     */
    <T, R> R lock(T data, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Function<T, R> action);

    <T> void lock(T data, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<T> action);

    /**
     * 单对象直接加锁执行，支持用户传入自定义函数式兜底降级。
     */
    <T, R> R lock(T data, Function<T, ?> keyExtractor, Function<T, R> action, Function<T, R> fallback);

    <T> void lock(T data, Function<T, ?> keyExtractor, Consumer<T> action, Consumer<T> fallback);

    // --- 带超时的单对象重载 ---
    <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Function<T, R> action);

    <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Consumer<T> action);

    <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Function<T, R> action);

    <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<T> action);

    <T, R> R lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Function<T, R> action, Function<T, R> fallback);

    <T> void lock(T data, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Consumer<T> action, Consumer<T> fallback);

    // =========================================================================
    // 2. 集合批量直接加锁执行（底层自动字典序排序消除死锁）
    // =========================================================================

    /**
     * 集合批量直接加锁执行（失败抛出默认固定友好提示异常）。
     */
    <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Function<C, R> action);

    <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Consumer<C> action);

    /**
     * 集合批量直接加锁执行，支持用户传入特制的友好错误提示文案。
     */
    <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, String errorMessage, Function<C, R> action);

    <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, String errorMessage, Consumer<C> action);

    /**
     * 集合批量直接加锁执行，支持用户传入特制的自定义业务异常工厂。
     */
    <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Function<C, R> action);

    <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<C> action);

    /**
     * 集合批量直接加锁执行，支持用户传入自定义函数式兜底降级。
     */
    <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, Function<C, R> action, Function<C, R> fallback);

    <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, Consumer<C> action, Consumer<C> fallback);

    // --- 带超时的集合批量重载 ---
    <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Function<C, R> action);

    <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, String errorMessage, Consumer<C> action);

    <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Function<C, R> action);

    <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Supplier<? extends RuntimeException> exceptionSupplier, Consumer<C> action);

    <T, C extends Collection<T>, R> R lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Function<C, R> action, Function<C, R> fallback);

    <T, C extends Collection<T>> void lock(C dataList, Function<T, ?> keyExtractor, long timeout, TimeUnit unit, Consumer<C> action, Consumer<C> fallback);
}
