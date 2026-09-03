# Distributed Lock 分布式锁组件

面向 Java 17 与 Spring Boot 3 的可扩展分布式锁组件，当前提供数据库租约锁和 Redis 原子锁两种存储实现。

项目采用“资源声明 → 链式配置 → 业务执行”三阶段 API。配置维度增加时只需扩展 `LockOperation`，不会产生组合式方法重载。

## 模块

```text
dist-lock-core                 核心 API、执行引擎、退避与看门狗
dist-lock-provider-db          数据库 CAS 租约实现
dist-lock-provider-redis       Redis SET NX PX 与 Lua 实现
dist-lock-spring-boot-starter  Spring Boot 自动配置与策略路由
dist-lock-example              可运行示例
```

## API 模型

### 1. 声明资源

```java
LockOperation operation = locker.lock(
    order,
    OrderDTO::getOrderId
);
```

批量资源：

```java
LockOperation operation = locker.lock(
    items,
    OrderItemDTO::getSkuCode
);
```

namespace 默认从资源对象的稳定用户类推导，业务方无需重复传入 `OrderDTO.class`。
需要区分同一实体上的多个独立锁域时，才通过 `.scope(OrderPaymentLock.class)` 使用专用空标记类。

最终锁键包含 namespace 的全限定类名、提取结果的实际类型和规范化业务键：

```text
dist-lock:v1:<namespace-class-name>:<key-class-name>:<business-key>
```

因此，不同锁域使用相同业务 ID，或者 `Long(1)` 与字符串 `"1"`，都不会意外成为同一把锁。
namespace 类的包名与类名属于持久协议，滚动发布期间不可随意重命名。

### 2. 按需链式配置

```java
LockOperation operation = locker
    .lock(order, Order::getOrderId)
    .strategy(LockStrategy.DATABASE)
    .waitTimeout(Duration.ofSeconds(3))
    .leaseTime(Duration.ofSeconds(30))
    .watchdog(true);
```

`LockOperation` 不可变。每个配置方法返回一个新对象，因此基础配置可以安全复用。

未显式设置的属性使用 Spring Boot 全局默认值：

```yaml
dist-lock:
  type: DATABASE
  default-wait-timeout: 3000
  default-lease-time: 30000
  watchdog-enabled: true
  watchdog-threads: 4
  database-operation-timeout: 3000
```

### 3. 执行业务

有返回值：

```java
OrderResult result = operation.call(() -> orderService.pay(order));
```

无返回值：

```java
operation.run(() -> orderService.cancel(order));
```

`call` 与 `run` 在锁竞争超时时抛出 `LockTimeoutException`。

需要自定义异常或降级时使用 `tryCall`：

```java
OrderResult result = operation
    .tryCall(() -> orderService.pay(order))
    .orElseThrow(() -> new BusinessException("订单正在处理中"));
```

```java
OrderResult result = operation
    .tryCall(() -> orderService.pay(order))
    .orElseGet(() -> OrderResult.busy(order));
```

`tryCall` 只把正常的锁竞争超时转换为 `LockOutcome.TIMEOUT`。存储故障、锁所有权丢失和业务异常不会被伪装成普通竞争失败。

关键写入可以取得本次 acquisition 的 fencing token：

```java
OrderResult result = locker
    .lock(order, Order::getOrderId)
    .callWithHandle(handle -> orderRepository.pay(
        order,
        handle.fencingToken()
    ));
```

token 由存储层对同一锁键单调递增。它只有在下游写入同时校验“只接受比已记录值更大的 token”时，才能阻止已失去租约的旧持有者覆盖新结果。批量锁可通过 `handle.leases()` 获取每个完整锁键及其 token。

## 策略路由

默认使用全局配置的存储策略；单次操作可以覆盖：

```java
boolean success = locker
    .lock(stock, SkuStock::getSkuCode)
    .strategy(LockStrategy.REDIS)
    .call(() -> stockService.deduct(skuCode));
```

如果请求的策略没有装配，执行时立即失败，不会随机回退到其他存储。

## Maven

当前版本为开发快照。使用 Starter 时还需要显式引入所需 Provider。

```xml
<dependency>
    <groupId>com.distlock</groupId>
    <artifactId>dist-lock-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>com.distlock</groupId>
    <artifactId>dist-lock-provider-db</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Redis 模式将 Provider 替换为 `dist-lock-provider-redis`。

## 数据库表

MySQL DDL 位于：

```text
dist-lock-provider-db/src/main/resources/schema/schema-mysql.sql
```

## 验证

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

GitHub Actions 会在 `main`、`fix/**`、`feat/**` 分支及 Pull Request 上自动执行验证。验证包含单元测试、MySQL/Redis Testcontainers 集成测试、JaCoCo 覆盖率门禁、SpotBugs 和 Maven Enforcer。

## 可观测性

引入 Spring Boot Actuator 与 Micrometer 后，Starter 自动提供健康检查和以下指标：

- `dist.lock.executions`：按策略和结果统计执行次数；
- `dist.lock.execution.duration`：锁操作耗时；
- `dist.lock.resources`：单次操作的资源数量；
- `dist.lock.watchdog.renewals`：续期成功、延迟、失败和丢锁次数；
- `dist.lock.watchdog.delay`：看门狗调度延迟。

健康检查会验证已装配 Provider 的连接，并报告各看门狗当前活跃任务数。

## 正确性边界

- 同一线程嵌套获取相同策略和锁键会快速失败；当前版本不支持重入。
- 批量锁键会去重并按完整键排序，消除循环等待。
- 每次 acquisition 使用独立 owner token，释放和续期必须匹配本次 owner。
- 看门狗续期失败超过租约边界或发现 owner 不匹配时，业务返回前会抛出 `LockLostException`。
- 数据库与 Redis Provider 都返回单调递增的 fencing token；支付、资金、库存等关键写入必须在下游持久化层校验 token，并继续保持业务幂等。

更完整的原理与扩展说明见 [技术规格](document/DOCUMENTATION.md)。
