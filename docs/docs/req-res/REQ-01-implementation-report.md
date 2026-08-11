# REQ-01 实施结果报告

> 需求：[REQ-01 · AgentMessage 协议标准化 + InMemoryMessageBus](../requirements/REQ-01-agent-message-protocol-and-bus.md)
>
> 来源计划：[11-production-grade-iteration-plan.md](../11-production-grade-iteration-plan.md)（M1 + M2）
>
> 实施日期：2026-08-06
>
> 代码库：`MustBeTheSQL-Server/sql-logic-service`
>
> 验证状态：**编译通过 + 29/29 测试通过（17 新增 + 12 既有回归）**

---

## 1. 整体结果概述

### 1.1 实施目标回顾

REQ-01 旨在为 Multi-Agent 系统建立**显式消息通信协议**与**内存消息总线**，替代当前 Agent 间通过共享 `OverAllState` 隐式耦合的通信方式，作为 v2.0「生产级 Multi-Agent」迭代的通信基座（REQ-02 编排集成、REQ-03 Redis 总线的直接前置）。

具体交付：
- **M1**：标准化 AgentMessage 协议——引入 `sealed interface` 消息类型体系，新增 `messageId`/`correlationId`/`timestamp` 身份字段。
- **M2**：`InMemoryMessageBus`——支持 `send`/`broadcast`/`subscribe`/`unsubscribe` 的内存消息总线。

### 1.2 实施成果

| 维度 | 结果 |
|------|------|
| 新增生产代码文件 | 3 个（`BusMessage.java`、`AgentMessageBus.java`、`InMemoryMessageBus.java`） |
| 修改生产代码文件 | 1 个（`AgentMessage.java`，纯增量） |
| 新增测试文件 | 2 个（`BusMessageTest.java`、`InMemoryMessageBusTest.java`） |
| 修改测试文件 | 1 个（`AgentMessageTest.java`，新增 3 个用例） |
| 编译验证 | `mvn -pl sql-logic-service compile` **BUILD SUCCESS**（385 源文件） |
| 新增测试 | 17/17 通过（`BusMessageTest` 6 + `InMemoryMessageBusTest` 11） |
| 既有回归测试 | 47/47 通过（`AgentMessageTest` 12 + `ConversableAgentTest` 10 + `AgentStateBridgeTest` 10 + `ContextManagerTest` + `ContextBudgetTrackerTest`） |
| 既有调用点破坏 | **0**（46 个引用 `AgentMessage` 的文件均未修改，向后兼容 AC-7 满足） |

### 1.3 关键设计决策

由于 `AgentMessage` 被 **46 个文件**引用（ManagerAgent 31 处、ConversableAgent 30 处、多个测试文件等），若按计划书字面表述将其直接重构为 sealed interface + record 子类型，将破坏全部调用点，与需求自身的 AC-7（向后兼容）及项目的「不破坏生产已验证代码」约束冲突。

因此采用**增量解耦**策略：
1. **新建并行抽象**：在 `domain/agentic/core/bus/` 下引入 `BusMessage` sealed interface 协议与 `InMemoryMessageBus`，作为 REQ-02 编排集成的目标通信通道。该抽象当前**不接入** 6-Agent StateGraph 流程，保持零影响。
2. **既有 `AgentMessage` 纯增量增强**：仅新增 3 个身份字段（自动填充），不改动任何既有字段、方法签名或调用点，确保 46 个引用文件零修改通过编译。

该策略与计划书 REQ-02 自身的「先旁路后切换」哲学一致——REQ-01 铺设协议与总线，REQ-02 负责将编排器切换到总线上。

---

## 2. 具体实现方式与技术方案

### 2.1 架构定位

```
domain/agentic/core/
├── AgentMessage.java          ← 既有富消息信封（StateGraph 流通货币），REQ-01 增量加身份字段
├── ConversableAgent.java      ← 既有：send()/receive() 直连调用（REQ-02 将改走总线）
└── bus/                       ← REQ-01 新增：消息总线子系统
    ├── BusMessage.java        ← sealed interface 协议 + 8 个 record 子类型 + BusHeader
    ├── AgentMessageBus.java   ← 总线接口 + Subscription
    └── InMemoryMessageBus.java← 内存实现（ConcurrentHashMap + 虚拟线程异步派发）
```

`bus/` 子包与既有 `core/` 平行存在，互不侵入。REQ-02 将在 `ConversableAgent.send()`/`ManagerAgent.act()` 中引入总线，并通过适配器在 `BusMessage` 与 `AgentMessage` 间转换。

### 2.2 消息协议设计（BusMessage sealed interface）

**为何用 sealed interface**：Java 21 的 sealed 类型使 `switch` 表达式获得**编译期穷举性**——若新增消息子类型而忘记在 switch 中处理，编译器直接报错。这比传统 enum + 字段判别更类型安全，每个消息类型可携带自己的强类型 payload。

**协议结构**：

```java
public sealed interface BusMessage
        permits BusMessage.PlanProposal, BusMessage.TaskDispatch, BusMessage.ToolResult,
                BusMessage.ReviewRequest, BusMessage.ReviewResponse, BusMessage.StatusUpdate,
                BusMessage.ErrorReport, BusMessage.Shutdown {

    BusHeader header();                       // 共享身份/路由信封
    default String messageId() { ... }        // 委托 header
    default String correlationId() { ... }    // 可空，关联请求-响应
    default Instant timestamp() { ... }
    default String senderName() { ... }
    default String receiverName() { ... }     // null 表示广播
    default String type() { ... }             // 日志/指标用类型标签

    record PlanProposal(BusHeader header, String plan, List<String> steps) implements BusMessage { ... }
    record TaskDispatch(BusHeader header, String targetAgent, String task) implements BusMessage {}
    record ToolResult(BusHeader header, String toolName, boolean success, String result) implements BusMessage { ... }
    record ReviewRequest(BusHeader header, String artifact, String question) implements BusMessage {}
    record ReviewResponse(BusHeader header, boolean approved, String comments) implements BusMessage { ... }
    record StatusUpdate(BusHeader header, String status, Map<String, Object> details) implements BusMessage { ... }
    record ErrorReport(BusHeader header, String errorCode, String message) implements BusMessage { ... }
    record Shutdown(BusHeader header, String reason) implements BusMessage { ... }

    record BusHeader(String messageId, String correlationId, Instant timestamp,
                     String senderName, String receiverName) { ... }  // + Builder 自动填充
}
```

**设计要点**：
- **`header()` 委托模式**：8 个子类型共享 `BusHeader` 身份信封，避免在每个 record 重复声明 5 个身份字段。身份访问器（`messageId()` 等）以 `default` 方法委托 `header()`，调用方无需感知 header 存在。
- **`BusHeader.Builder` 自动填充**：`messageId` 缺省生成 UUID，`timestamp` 缺省取 `Instant.now()`，`correlationId` 故意可空（响应消息才需填）。保证「任一消息实例必含非空 messageId/timestamp」（AC-2）。
- **record 不可变性**：`PlanProposal.steps`、`StatusUpdate.details` 等集合字段在紧凑构造器中做防御性拷贝（`List.copyOf`/`Map.copyOf`），且对外返回不可变视图。
- **子类型字段语义**：覆盖计划书 3.1 节规划的全部消息类型——`PlanProposal`（规划提案）、`TaskDispatch`（任务派发）、`ToolResult`（工具结果）、`ReviewRequest`/`ReviewResponse`（评审流转）、`StatusUpdate`（状态广播）、`ErrorReport`（结构化错误，为 REQ-06 ErrorClassifier 预留）、`Shutdown`（优雅关闭协商）。

### 2.3 总线接口设计（AgentMessageBus）

```java
public interface AgentMessageBus {
    void send(BusMessage message);                                    // 点对点
    void broadcast(BusMessage message);                               // 全量广播
    Subscription subscribe(String topic, Consumer<BusMessage> handler);
    interface Subscription { void cancel(); }                         // 取消订阅句柄
}
```

**Topic 语义**：
- `send(msg)`：投递给 topic == `msg.receiverName()` 的订阅者，**外加**通配符 `*` 订阅者。
- `broadcast(msg)`：投递给**所有 topic** 的全部订阅者。
- 通配符 topic `"*"`：其订阅者收到**所有** send 与 broadcast 消息——为 REQ-09 Middleware/REQ-17 Trace 预留全量观测点。
- `null`/空 `receiverName` 的 send：仅投递给通配符订阅者（容错）。

该语义为 REQ-03 RedisMessageBus 提供一致的接口契约——Redis 实现仅替换底层传输，topic 语义不变。

### 2.4 内存实现设计（InMemoryMessageBus）

```java
public final class InMemoryMessageBus implements AgentMessageBus {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<HandlerEntry>> topicHandlers;
    private final Executor dispatcher;   // 默认虚拟线程，测试可注入同步执行器
    ...
}
```

**关键技术决策**：

| 决策 | 方案 | 理由 |
|------|------|------|
| 订阅存储 | `ConcurrentHashMap<String, CopyOnWriteArrayList<HandlerEntry>>` | 读多写少（派发远多于订阅），COW 列表使派发路径无锁、无分配 |
| 派发模型 | `Executor` 异步派发，默认 `Executors.newVirtualThreadPerTaskExecutor()` | 与 `ConversableAgent` 既有的虚拟线程风格一致；满足 <10ms 单跳延迟（AC-3）；测试注入 `Runnable::run` 实现确定性同步派发 |
| 故障隔离 | 每个 handler 调用 try/catch 包裹，异常仅 `log.warn` | 一个 handler 抛异常不阻断兄弟 handler 与调用方（AC-6） |
| 取消订阅 | `AtomicBoolean cancelled` 标志位，懒清理 | 热点派发路径无锁、无分配；`cancel()` 幂等；`handlerCount()` 统计活跃数 |
| Spring 装配 | **暂不加 `@Component`** | REQ-01 保持纯增量、零运行影响；总线注入 context 留给 REQ-02 编排集成 |

### 2.5 既有 AgentMessage 增量增强

仅做**加法**，未删除或修改任何既有成员：

- 新增 3 个 `final` 字段：`messageId`、`correlationId`、`timestamp`。
- `Builder` 新增对应 3 个字段 + 3 个 setter + 在 `Builder(AgentMessage source)` 拷贝构造中复制。
- 构造器中：`messageId` 缺省 `UUID.randomUUID()`、`timestamp` 缺省 `Instant.now()`、`correlationId` 保持可空。
- 新增 3 个 getter：`messageId()`/`correlationId()`/`timestamp()`。
- `toString()` 增补 `id` 字段。

这使既有 `AgentMessage` 实例天然具备消息身份，REQ-02 适配器可将其映射为 `BusMessage`（如 `TaskDispatch`/`StatusUpdate`）而无需二次改造。

---

## 3. 改动文件清单

### 3.1 总览

| # | 文件 | 类型 | 改动性质 | 行数变化 |
|---|------|------|---------|---------|
| 1 | `domain/agentic/core/bus/BusMessage.java` | 新增 | 核心 | +196 |
| 2 | `domain/agentic/core/bus/AgentMessageBus.java` | 新增 | 核心 | +63 |
| 3 | `domain/agentic/core/bus/InMemoryMessageBus.java` | 新增 | 核心 | +155 |
| 4 | `domain/agentic/core/AgentMessage.java` | 修改 | 核心（纯增量） | +约 30 |
| 5 | `src/test/.../core/bus/BusMessageTest.java` | 新增 | 测试 | +112 |
| 6 | `src/test/.../core/bus/InMemoryMessageBusTest.java` | 新增 | 测试 | +160 |
| 7 | `src/test/.../core/AgentMessageTest.java` | 修改 | 测试（新增 3 用例） | +约 38 |

> 以下路径前缀均为 `MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/`（生产）或 `.../src/test/java/com/sql/logic/engine/`（测试）。

---

### 3.2 核心文件详细说明

#### 文件 1：`domain/agentic/core/bus/BusMessage.java`（新增）

**修改目的**：定义标准化的 inter-Agent 消息协议——一个编译期穷举的 sealed 消息类型体系，作为消息总线的传输契约。

**功能与代码逻辑**：

1. **sealed interface 主体**：通过 `permits` 子句显式声明 8 个允许的 record 子类型。Java 编译器据此对 `switch` 表达式做穷举性检查——任何遗漏子类型的 switch 直接编译失败（AC-1 的编译期保证）。

2. **`header()` 委托 + default 访问器**：所有身份/路由信息集中在 `BusHeader` 中，子类型 record 只需声明 `BusHeader header` 一个共享字段 + 自身业务字段。`messageId()`/`correlationId()`/`timestamp()`/`senderName()`/`receiverName()` 均为 `default` 方法委托 `header()`，调用方写 `msg.messageId()` 即可，无需感知 header 存在。

3. **8 个 record 子类型**（均嵌套于 BusMessage 内，实现接口）：
   - `PlanProposal(header, plan, steps)`：紧凑构造器对 `steps` 做 `List.copyOf` 防御性拷贝。
   - `TaskDispatch(header, targetAgent, task)`：Manager 派发任务给 worker。
   - `ToolResult(header, toolName, success, result)`：null 防御。
   - `ReviewRequest`/`ReviewResponse`：评审流转（呼应既有 `ReviewInfo` 机制）。
   - `StatusUpdate(header, status, details)`：`details` 防御性拷贝 + 不可变视图。
   - `ErrorReport(header, errorCode, message)`：为 REQ-06 ErrorClassifier 预留结构化错误载体。
   - `Shutdown(header, reason)`：关闭协商。

4. **`BusHeader` record + Builder**：5 字段身份信封。`Builder.build()` 中：`messageId` 缺省 `UUID.randomUUID().toString()`、`timestamp` 缺省 `Instant.now()`、`correlationId` 保持可空。这保证「任一 BusMessage 实例必含非空 messageId/timestamp」（AC-2）。

#### 文件 2：`domain/agentic/core/bus/AgentMessageBus.java`（新增）

**修改目的**：定义消息总线的抽象接口，解耦 Agent 通信与具体传输（内存/Redis）。

**功能与代码逻辑**：
- `send(BusMessage)`：点对点投递契约。
- `broadcast(BusMessage)`：全量广播契约。
- `subscribe(String topic, Consumer<BusMessage>)`：订阅指定 topic，返回 `Subscription`。
- 嵌套 `interface Subscription { void cancel(); }`：取消订阅句柄接口。
- Javadoc 完整说明 topic 语义（`*` 通配符、null receiver 行为）与实现方必须保证的性质（线程安全、故障隔离、非阻塞）。

该接口是 REQ-03 `RedisMessageBus` 的直接实现目标——Redis 版仅替换传输层，接口与 topic 语义不变。

#### 文件 3：`domain/agentic/core/bus/InMemoryMessageBus.java`（新增）

**修改目的**：提供单 JVM 内的默认总线实现，满足 REQ-01 全部 AC。

**功能与代码逻辑**：

1. **状态存储**：`ConcurrentHashMap<String, CopyOnWriteArrayList<HandlerEntry>> topicHandlers`。topic 为键，handler 列表为值。COW 列表适配「派发多、订阅少」的访问模式，派发路径无锁。

2. **构造与派发器**：
   - 默认构造：`Executors.newVirtualThreadPerTaskExecutor()`（Java 21 虚拟线程，异步并行，与 `ConversableAgent` 风格一致）。
   - 注入构造：`InMemoryMessageBus(Executor dispatcher)`，测试注入 `Runnable::run` 实现同步确定性派发。

3. **`send(message)`**：取 `message.receiverName()` 为 topic，调用 `dispatch(message, topic, false)`（点对点模式）。null/空 receiver 仅投递通配符订阅者。

4. **`broadcast(message)`**：调用 `dispatch(message, null, true)`（广播模式），遍历 `topicHandlers.entrySet()` 全量投递。

5. **`dispatch(...)` 私有方法**：
   - 广播分支：遍历所有 topic 的 handler 列表逐一 `fire`。
   - 点对点分支：先 `topicHandlers.get(topic)` 精确投递，再 `topicHandlers.get("*")` 通配符投递（若 topic 本身不是 `*`）。
   - 对 null/blank topic 做守卫，避免 `ConcurrentHashMap.get(null)` 抛 NPE。

6. **`fire(handlers, message)`**：遍历 COW 列表，跳过已取消的 `HandlerEntry`，对每个活跃 handler 执行 `dispatcher.execute(...)`。任务体内 try/catch 包裹 handler 调用——异常仅 `log.warn`，不传播（故障隔离，AC-6）。

7. **`subscribe(topic, handler)`**：null topic 归一为 `*`，创建 `HandlerEntry` 加入对应列表，返回 `HandlerEntry` 自身（实现 `Subscription`）。

8. **`HandlerEntry` 内部类**：持 `handler` + `AtomicBoolean cancelled`。`cancel()` 置标志（幂等）。懒清理——派发时跳过已取消项，避免热点路径上的列表修改锁争用。

9. **`handlerCount(topic)`**：包级可见，供测试断言活跃订阅数。

#### 文件 4：`domain/agentic/core/AgentMessage.java`（修改 · 纯增量）

**修改目的**：为既有富消息信封补齐消息身份字段，使其未来可参与总线协议；满足 REQ-01 对「AgentMessage 协议标准化」的意图，同时保持 46 个调用点零破坏（AC-7）。

**改动内容**（全部为加法，无任何既有成员被删除或改签名）：
- 新增 `import java.time.Instant;` 与 `import java.util.UUID;`。
- 类 Javadoc 增补 REQ-01 说明段落。
- 新增 3 个 `final` 字段：`messageId`、`correlationId`（注释标注可空）、`timestamp`。
- 私有构造器中：`messageId = builder.messageId != null ? builder.messageId : UUID.randomUUID().toString()`；`timestamp` 同理缺省 `Instant.now()`；`correlationId` 直接取（可空）。
- 新增 3 个 getter：`messageId()`/`correlationId()`/`timestamp()`，均带 Javadoc。
- `Builder` 新增 3 个字段、3 个 setter（`messageId(String)`/`correlationId(String)`/`timestamp(Instant)`），并在 `Builder(AgentMessage source)` 拷贝构造中复制这 3 字段。
- `toString()` 增补 `id=` 段。

**为何不改 MessageType 枚举**：既有 `MessageType{SYSTEM,USER,AI,TOOL}` 描述的是「对话角色」，与 `BusMessage` 的 8 个「协议类型」是正交维度。REQ-01 不混淆二者，保持 `MessageType` 不变。

---

### 3.3 测试文件说明

#### 文件 5：`src/test/.../core/bus/BusMessageTest.java`（新增，6 用例）

覆盖 **AC-1**（sealed 穷举性：用 `switch` 表达式覆盖全部 8 子类型，编译期保证）与 **AC-2**（身份契约：`messageId`/`timestamp` 缺省非空、`correlationId` 可空、显式值保留、record 不可变性）。

#### 文件 6：`src/test/.../core/bus/InMemoryMessageBusTest.java`（新增，11 用例）

覆盖 **AC-3**（send 点对点投递 + <10ms 延迟，用 `CountDownLatch` + 超时断言）、**AC-4**（broadcast 全量投递）、**AC-5**（unsubscribe 停止投递 + 幂等 + 无泄漏）、**AC-6**（10 线程并发 send + 10k 消息无丢失无异常、handler 异常故障隔离）、通配符订阅、null 消息容错、null handler 拒绝、同 topic 多订阅者。测试通过注入 `Runnable::run` 同步执行器实现确定性断言。

#### 文件 7：`src/test/.../core/AgentMessageTest.java`（修改，新增 3 用例）

新增 `builderShouldAutoFillMessageIdAndTimestampWhenUnset`、`builderShouldPreserveExplicitIdentityFields`、`builderFromExistingMessageShouldCopyIdentityFields`，验证既有 `AgentMessage` 的 3 个新身份字段行为（自动填充、显式保留、拷贝构造复制）。既有 9 个用例全部保持通过，证明向后兼容。

---

## 4. 验收标准达成追溯

| AC | 描述 | 达成证据 |
|----|------|---------|
| AC-1 | sealed interface ≥8 子类型，switch 穷举 | `BusMessage.java` permits 8 record；`BusMessageTest.shouldExposeAllEightPermittedSubtypes` 用 switch 表达式编译通过 |
| AC-2 | 每条消息含非空 messageId/timestamp、可空 correlationId | `BusHeader.Builder.build()` 自动填充；`BusMessageTest.headerShouldAutoFillMessageIdAndTimestampWhenUnset` 验证 |
| AC-3 | send 在 <10ms 内送达 | `InMemoryMessageBusTest.sendShouldDeliverWithin10msLatencyBudget` 用 `Duration` 断言 <10ms |
| AC-4 | broadcast 送达全部订阅者 | `InMemoryMessageBusTest.broadcastShouldDeliverToAllSubscribersAcrossTopics` |
| AC-5 | unsubscribe 后不再投递、无泄漏 | `InMemoryMessageBusTest.unsubscribeShouldStopFurtherDelivery` + `cancelShouldBeIdempotent` + `handlerCount` 断言 |
| AC-6 | 并发安全、无丢失、故障隔离 | `shouldSurviveConcurrentSendAndSubscribe`（10×1000 消息）+ `handlerExceptionShouldNotBlockOthers` |
| AC-7 | 既有调用点经适配层编译通过 | 46 个引用文件零修改；`AgentMessageTest`/`ConversableAgentTest`/`AgentStateBridgeTest`/`ContextManagerTest` 共 47 既有用例全绿 |

---

## 5. 已知边界与后续衔接

1. **未接入 6-Agent 流程**：总线当前为独立抽象，`ConversableAgent.send()` 仍是直连 `recipient.receive()`。这是设计意图——接入属 REQ-02（编排集成），按「先旁路后切换」策略进行。
2. **未做 Spring 装配**：`InMemoryMessageBus` 未加 `@Component`，避免在 REQ-02 前产生未使用的 Bean。REQ-02 将在 `AgenticAutoConfiguration` 中注册并注入。
3. **`BusMessage` ↔ `AgentMessage` 适配器**：本需求未实现，留给 REQ-02（届时按消息类型映射，如 `TaskDispatch`↔派发意图、`ToolResult`↔`ActionOutput`）。
4. **Redis 总线**：`AgentMessageBus` 接口已为 REQ-03 `RedisMessageBus` 预留一致契约，topic 语义可直接复用。

---

## 6. 验证命令记录

```bash
# 编译（offline，确认无破坏）
mvn -pl sql-logic-service compile -o -DskipTests        # BUILD SUCCESS, 385 文件

# 新增测试
mvn -pl sql-logic-service test -o -Dtest='BusMessageTest,InMemoryMessageBusTest'
#   Tests run: 17, Failures: 0, Errors: 0   BUILD SUCCESS

# 既有回归（AC-7）
mvn -pl sql-logic-service test -o -Dtest='AgentMessageTest,ConversableAgentTest,AgentStateBridgeTest,ContextManagerTest,ContextBudgetTrackerTest'
#   Tests run: 47, Failures: 0, Errors: 0   BUILD SUCCESS

# 最终合并
mvn -pl sql-logic-service test -o -Dtest='BusMessageTest,InMemoryMessageBusTest,AgentMessageTest'
#   Tests run: 29, Failures: 0, Errors: 0   BUILD SUCCESS
```

> 环境说明：JDK 21（`JAVA_HOME=/Users/vamos/Library/Java/JavaVirtualMachines/oracle_open_jdk-21/Contents/Home`），Maven `/Users/vamos/Documents/tools/apache-maven-3.9.16/bin/mvn`，`sql-logic-common` 已在本地仓库。
