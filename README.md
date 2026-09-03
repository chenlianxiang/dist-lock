# Distributed Lock 分布式锁通用组件

本项目为多集群环境下的 Java 分布式锁通用组件，支持数据库 CAS 租约与 Redis 原子 Lua 脚本双存储底座，提供全限定类名防冲撞、集合升序防死锁、看门狗自动续期及开闭原则策略路由。

完整技术原理、数学推导与架构规格请参阅：[设计与技术规格说明书 (document/DOCUMENTATION.md)](document/DOCUMENTATION.md)。

---

## 模块结构

```text
dist-lock/
├── dist-lock-core                     # 核心抽象层：门面接口、策略模型、退避算法、看门狗引擎
├── dist-lock-provider-db              # 关系型数据库存储实现：ANSI-SQL CAS 租约
├── dist-lock-provider-redis           # Redis 存储实现：原子指令与 Lua 脚本
├── dist-lock-spring-boot-starter      # Spring Boot 自动装配与路由容器装配
└── dist-lock-example                  # 实战案例工程
```

---

## 核心设计规格

1. **统一门面交互**：
   * 入口为 `locker.lock(...)`，单对象与集合批量共享统一执行引擎；
   * 加锁与业务执行同步完成，直接返回业务结果。
2. **多底座混合路由**：
   * 采用 `LockStrategy` 策略接口，默认路由至关系型数据库（`DATABASE`）；
   * 高频业务通过 `locker.use(LockStrategy.REDIS)` 切换至 Redis；
   * 遵循开闭原则，扩展新底座无需修改核心接口。
3. **全限定类名隔离**：
   * 锁键格式为 `<Package.ClassName>:<BusinessKey>`，自动剥离动态代理后缀；
   * 依靠 JVM 类命名规则消除不同业务实体的主键冲突。
4. **集合排序防死锁**：
   * 批量加锁前执行键去重与字典序升序排列，破坏环路等待条件；
   * 遇到获取失败时按逆序释放已持有锁。
5. **数据库连接保护与时钟统一**：
   * 采用单条 CAS `UPDATE` 语句更新租约，单次交互完成后即归还连接池；
   * 依据数据库服务端时间戳 (`SELECT CURRENT_TIMESTAMP`) 计算租约截止时间，消除服务器时钟漂移。
6. **多维度降级处理**：
   * 默认固定异常提示：`系统繁忙，当前业务正在处理中，请稍候重试`；
   * 支持传入自定义提示文本、自定义业务异常工厂（`Supplier`）及函数式返回值降级（`fallback`）。

---

## 快速开始

### 1. Maven 依赖

```xml
<dependency>
    <groupId>com.distlock</groupId>
    <artifactId>dist-lock-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 数据库 DDL (MySQL)

```sql
CREATE TABLE IF NOT EXISTS `dist_lock` (
  `lock_key` VARCHAR(255) NOT NULL COMMENT '锁资源唯一标识(全限定类名:业务主键)',
  `owner` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '锁持有者唯一标识(Node:PID:ThreadId)',
  `expire_time` BIGINT NOT NULL DEFAULT 0 COMMENT '绝对过期时间戳(毫秒)',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`lock_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用分布式锁租约表';
```

---

## 使用示例

### 1. 常规业务（默认走数据库锁）

```java
@Autowired
private DistributedLocker locker;

// 加锁执行并直接返回业务结果
OrderResult result = locker.lock(
    order, 
    OrderDTO::getOrderId, 
    "当前订单正在支付中，请勿重复操作", 
    o -> orderService.pay(o)
);
```

### 2. 高频秒杀业务（自主选择 Redis 策略）

```java
boolean success = locker.use(LockStrategy.REDIS).lock(
    seckillItem, 
    SeckillDTO::getSkuCode, 
    "商品正在抢购中，请稍后再试", 
    item -> seckillService.deductStock(item)
);
```

### 3. 抛出自定义业务异常

```java
OrderResult result = locker.lock(
    order, 
    OrderDTO::getOrderId, 
    () -> new BusinessException(10001, "账户资金已锁定"), 
    o -> orderService.pay(o)
);
```

### 4. 函数式值降级

```java
OrderResult result = locker.lock(
    order, 
    OrderDTO::getOrderId, 
    3, TimeUnit.SECONDS,
    o -> orderService.pay(o),              // 正常执行
    o -> OrderResult.busy(o)               // 超时降级
);
```

### 5. 集合批量加锁（自动防死锁）

```java
boolean success = locker.lock(
    items, 
    OrderItemDTO::getSkuCode, 
    "购物车部分商品结算冲突，请重试", 
    list -> stockService.deductBatch(list)
);
```

---

## 运行示例工程

```bash
# 启动内置 H2 演示应用
mvn spring-boot:run -pl dist-lock-example

# 执行全模块自动化测试
mvn clean test
```
