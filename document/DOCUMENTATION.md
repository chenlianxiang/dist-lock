# 分布式锁技术规格

## 1. 设计目标

组件将三个相互独立的关注点分开：

1. `DistributedLocker.lock`：通过唯一入口声明单个或批量业务资源；
2. `LockOperation`：不可变的链式配置；
3. `call/run/tryCall`：终止配置并执行业务。

公开接口只有一个 `lock(resourceOrResources, keyExtractor)`，不再通过重载排列单对象、集合、超时、租期、策略、返回值和失败处理。单对象在内部包装为单元素集合，之后统一执行校验、取键、去重、排序、获取、续期和释放。增加配置项时只扩展 `LockOperation`，不会复制执行方法。

## 2. API 生命周期

```mermaid
flowchart TD
    A[声明唯一 lock] --> B[统一资源集合]
    B --> C[生成 LockOperation]
    C --> D[链式覆盖策略与租约]
    D --> E{终止操作}
    E -->|call| F[执行并返回]
    E -->|run| G[执行无返回业务]
    E -->|tryCall| H[返回 LockOutcome]
```

示例：

```java
LockOperation paymentLock = locker
    .lock(order, Order::getOrderId)
    .strategy(LockStrategy.DATABASE)
    .waitTimeout(Duration.ofSeconds(3))
    .leaseTime(Duration.ofSeconds(30))
    .watchdog(true);

OrderResult result = paymentLock.call(() -> paymentService.pay(order));
```

配置阶段不会访问存储。只有调用 `call`、`run` 或 `tryCall` 才开始获取锁。

## 3. 锁键规范

调用方提供业务对象和取键函数，namespace 默认从资源对象的稳定用户类推导：

```java
locker.lock(order, Order::getOrderId);
locker.lock(stocks, SkuStock::getSkuCode);
```

锁键由 namespace 的全限定类名、取键结果的实际类型和业务键组成：

```text
qualified-key = "dist-lock:v1:" + namespace-class-name + ":" + key-class-name + ":" + business-key
```

组件会剥离常见 CGLIB/Hibernate 代理子类，尽量解析为稳定用户类。不同资源类型即使提取出相同值，也不会发生无关锁竞争。字符串 `"1"` 与数值 `1` 也属于不同锁键。

同一实体若存在互不影响的锁域，应定义专用标记类：

```java
final class OrderPaymentLock {}
final class OrderCancelLock {}

locker.lock(order, Order::getOrderId)
    .scope(OrderPaymentLock.class)
    .call(() -> paymentService.pay(order));
```

标记类的全限定名是跨节点协议。包名或类名变更会创建新的锁域，因此滚动发布期间不可直接重命名。

约束：

- 业务对象、取键函数和业务键不能为空；
- 默认业务键仅接受 String、Number、UUID、enum、boolean 和 character 等稳定值类型；
- 批量资源不能包含 null；
- 完整锁键最长255字符，以兼容数据库表结构；
- 批量键在配置阶段完成去重和全局排序。

## 4. 执行模型

一次执行生成独立 owner：

```text
host:node-uuid:pid:thread-id:acquisition-uuid
```

执行流程：

1. 检查当前线程是否已持有相同策略和锁键；
2. 使用单调时钟建立整批资源的等待预算；
3. 按排序后的完整键逐个获取；
4. 每获得一把锁立即启动看门狗；
5. 全部获得后统一续期一次，确认所有权仍有效；
6. 执行业务闭包；
7. 停止续期并逆序释放。

部分获取失败时执行的是补偿释放，不承诺底层存储层面的原子事务。

## 5. 结果与异常语义

`LockOutcome` 只表达两种正常业务路径：

| 状态 | 含义 |
|---|---|
| `ACQUIRED` | 已获得全部锁并完成业务执行 |
| `TIMEOUT` | 在等待预算内未获得全部锁，业务未执行 |

规则：

- `call`、`run`：TIMEOUT 转换为 `LockTimeoutException`；
- `tryCall`：调用方通过 `orElse`、`orElseGet`、`orElseThrow`选择业务处理；
- 业务闭包异常原样传播；
- 存储不可用和所有权丢失不得降级为 TIMEOUT。

## 6. 数据库存储

数据库实现使用租约记录：

```sql
UPDATE dist_lock
SET owner = ?, expire_time = ?, version = version + 1
WHERE lock_key = ? AND expire_time < ?
```

不存在记录时通过主键唯一约束竞争 INSERT。当前版本明确不支持重入，相同 owner 也不能覆盖有效租约。

释放和续期均校验 owner：

```sql
UPDATE dist_lock SET owner = '', expire_time = 0
WHERE lock_key = ? AND owner = ?;
```

## 7. Redis 存储

获取使用 `SET key owner NX PX leaseMillis`。

释放：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
```

续期：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('pexpire', KEYS[1], ARGV[2])
end
return 0
```

Java 调用必须依次传入 owner 和 leaseMillis。

## 8. 策略路由

`RoutingDistributedLocker` 在终止执行时读取操作配置：

- 未配置 strategy：使用全局默认策略；
- 已配置 strategy：使用本次操作指定策略；
- 目标策略未注册：立即抛出配置异常。

链式配置对象本身不绑定 Spring Bean，创建后可以安全传递和复用。

## 9. 当前边界与后续演进

租约锁无法阻止已经失去租约的旧业务继续写入。生产关键写入需要 fencing token 或等价的业务版本校验。

后续设计将在不增加执行重载的前提下扩展：

```java
locker.lock(stock, SkuStock::getSkuCode)
    .fencing(FencingPolicy.REQUIRED)
    .observability(observation)
    .call(handle -> inventoryService.deduct(skuId, handle.fencingToken()));
```

该阶段需要同步升级存储 SPI 和业务写入契约，在 Issue #1 中单独验收。
