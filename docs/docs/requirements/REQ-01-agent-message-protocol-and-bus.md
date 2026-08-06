# REQ-01 · AgentMessage 协议标准化 + InMemoryMessageBus

| 字段 | 值 |
|------|-----|
| 来源 | M1（标准化 AgentMessage 协议）+ M2（InMemoryMessageBus） |
| 优先级 | **P0** |
| 工作量 | 7 人天 |
| 依赖前置 | 无 |
| 被依赖 | REQ-02、REQ-03、REQ-06（软）、REQ-09（软） |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-A / W1 |

## 1. 功能描述

将当前的 `AgentMessage`（普通 class + 4 值枚举）重构为 **`sealed interface` 消息类型体系**，并实现 `InMemoryMessageBus` 替代 Agent 间通过共享 `OverAllState` 隐式通信的方式。消息总线提供 `send` / `broadcast` / `subscribe` 语义，使 Agent 通信从"共享内存 key 依赖"升级为"显式消息契约"。

## 2. 背景与动机

现状：[AgentMessage.java](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/core/AgentMessage.java) 是普通不可变类，`MessageType` 仅 SYSTEM/USER/AI/TOOL 四类，无 `messageId`/`correlationId`/`timestamp`。[AgentOrchestrator.java](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/config/AgentOrchestrator.java) 中 6 个 Agent 节点经 `OverAllState` + `ReplaceStrategy` 隐式耦合，无通信契约。

痛点：隐式 key 依赖导致协作链路不可观测、不可重放、不可跨进程（阻碍 REQ-03 Redis 总线与 REQ-07 Checkpoint 恢复）。

## 3. 用户故事

- **US-1**：作为 Agent 框架开发者，我希望 Agent 间消息有统一类型契约（sealed interface），这样我能在编译期穷举所有消息类型，避免遗漏处理分支。
- **US-2**：作为平台运维者，我希望每条消息带 `messageId`/`correlationId`/`timestamp`，这样我能端到端追踪一次协作的消息链路。
- **US-3**：作为 Agent 实现者，我希望通过 `bus.subscribe(topic, handler)` 收消息、`bus.send(message)` 发消息，这样 Agent 不再直接读写共享 state，职责清晰。
- **US-4**：作为测试编写者，我希望 InMemoryMessageBus 可被注入 mock，这样我能单元测试单 Agent 的消息处理而无需启动整图。

## 4. 功能性验收标准

- **AC-1（消息类型穷举）**：GIVEN sealed interface `AgentMessage` 定义了 ≥8 种 permitted 子类型（PlanProposal / TaskDispatch / ToolResult / ReviewRequest / ReviewResponse / StatusUpdate / ErrorReport / Shutdown 等）；WHEN 用 `switch` 模式匹配处理；THEN 编译器对未覆盖分支报错（穷举性保证）。
- **AC-2（消息标识）**：GIVEN 任一 AgentMessage 实例；THEN 必含非空 `messageId`（UUID）、`correlationId`（可空，关联请求）、`timestamp`（Instant）、`senderName`、`receiverName`（可空表广播）。
- **AC-3（总线 send）**：GIVEN Agent A `bus.send(msg)` 且 msg.receiver=B；WHEN Agent B 已 subscribe；THEN B 的 handler 在 <10ms 内收到原样 msg（内存总线延迟目标）。
- **AC-4（总线 broadcast）**：GIVEN `bus.broadcast(msg)`；WHEN 3 个 Agent 已 subscribe 该 topic；THEN 三者均收到 msg，且互不阻塞（并行派发）。
- **AC-5（subscribe 解绑）**：GIVEN handler 已 subscribe；WHEN 调用 unsubscribe；THEN 后续 send 不再触发该 handler，无内存泄漏。
- **AC-6（线程安全）**：GIVEN 10 线程并发 send + 10 线程并发 subscribe/unsubscribe；THEN 无 ConcurrentModificationException、无消息丢失（压力测试 10k 条）。
- **AC-7（向后兼容）**：GIVEN 现有 6-Agent StateGraph 仍可运行（REQ-02 尚未切换）；THEN AgentMessage 旧调用点能经适配层编译通过。

## 5. 非功能性要求

- **性能**：内存总线单跳派发延迟 <10ms（计划书 1.1 指标）。
- **线程安全**：内部用 `ConcurrentHashMap` + `CopyOnWriteArrayList`，遵循项目"`ConcurrentHashMap` 替代 LinkedHashMap"约定（见 mcp-multiagent-refactor）。
- **依赖**：仅 JDK 17+ sealed interface + Spring；不引入新消息中间件客户端。
- **可测试性**：总线接口与实现分离，便于 REQ-03 Redis 实现替换。

## 6. 技术实现要点

- 新增 `domain/agentic/core/bus/AgentMessageBus.java`（接口：send/broadcast/subscribe/unsubscribe）。
- 新增 `domain/agentic/core/bus/InMemoryMessageBus.java`（默认实现，topic→handlers 映射）。
- 重构 `AgentMessage.java` 为 sealed interface + record 子类型，保留旧 Builder 作适配层（`AgentMessage.builder()` 返回兼容 AI 消息）。
- `correlationId` 由 `ManagerAgent` 在 dispatch 时生成，worker 响应时回填。

## 7. 范围边界（不做）

- 不改造 StateGraph 通信（属 REQ-02）。
- 不实现 Redis 总线（属 REQ-03）。
- 不引入消息持久化（属 REQ-07 Checkpoint 范畴）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| sealed interface 重构破坏旧 AgentMessage 调用点 | 保留 Builder 适配层，编译期暴露全部调用点逐一迁移 |
| 内存总线广播阻塞慢消费者 | handler 异步派发（线程池），单 handler 异常不阻断他人 |
