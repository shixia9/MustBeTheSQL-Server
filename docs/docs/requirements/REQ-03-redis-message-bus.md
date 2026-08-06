# REQ-03 · RedisMessageBus（持久化消息 + Pub/Sub）

| 字段 | 值 |
|------|-----|
| 来源 | M17 |
| 优先级 | P1 |
| 工作量 | 4 人天 |
| 依赖前置 | REQ-01、REQ-07（Checkpoint，提供"断点续跑"基础） |
| 被依赖 | 无 |
| 关联已有 spec | 无（注：框架 `RedisSaver` 是 Checkpoint 持久化，与本需求"消息总线持久化"不同概念，勿混） |
| 里程碑 | Phase 7-C / W7 |

## 1. 功能描述

实现 `AgentMessageBus` 的 Redis 后端 `RedisMessageBus`，基于 Redis Stream 提供消息持久化与 Pub/Sub，支撑跨进程 Agent 通信与断点续跑场景。内存总线（REQ-01）仍为默认实现，Redis 为可选增强。

## 2. 背景与动机

REQ-01 的 InMemoryMessageBus 无法跨进程、重启即丢。生产级场景需：①多实例部署时 Agent 可跨实例通信；②长任务中断后续跑时消息可重放。Redis Stream 兼具持久化与消费组语义，是计划书 1.1 指标"<50ms（Redis）"的载体。

## 3. 用户故事

- **US-1**：作为 SRE，我希望消息总线可切换为 Redis 后端，这样多实例部署时 Agent 通信不限于单 JVM。
- **US-2**：作为长任务用户，我希望任务中断重启后能从断点续跑，这样耗时分析任务不需从头开始。
- **US-3**：作为运维者，我希望 Redis 不可用时系统自动降级到内存总线，这样 Redis 故障不阻断服务。

## 4. 功能性验收标准

- **AC-1（实现契约）**：GIVEN `RedisMessageBus implements AgentMessageBus`；THEN send/broadcast/subscribe 语义与 InMemoryMessageBus 一致（REQ-01 AC-3/4/5 复用）。
- **AC-2（持久化）**：GIVEN send 一条消息后 JVM 重启；WHEN 重新 subscribe；THEN 能从 Stream 消费到该消息（断点续跑）。
- **AC-3（延迟）**：GIVEN Redis 总线；WHEN send；THEN 单跳派发延迟 <50ms（计划书 1.1 指标）。
- **AC-4（降级）**：GIVEN Redis 连接失败；WHEN 启动；THEN 日志告警且系统降级为 InMemoryMessageBus，服务可用。
- **AC-5（消费组）**：GIVEN 多实例 subscribe 同一 topic；THEN 消息按消费组负载均衡，不重复消费。

## 5. 非功能性要求

- **依赖**：确认 docker-compose Redis 版本 ≥5.0（Stream 支持）——计划书 5.3 问题 2 需在此需求前结案。
- **配置**：`agent.message-bus.type=memory|redis`，默认 memory。
- **序列化**：AgentMessage 经 JSON 序列化入 Stream，需兼容 REQ-01 sealed interface 的多态反序列化（@JsonTypeInfo）。

## 6. 技术实现要点

- 新增 `domain/agentic/core/bus/RedisMessageBus.java`（spring-data-redis Stream + 消费组）。
- 复用 REQ-02 的 `bus` 抽象，仅替换实现 Bean（@ConditionalOnProperty）。
- 断点续跑与 REQ-07 Checkpoint 配合：Checkpoint 恢复线程状态 + Redis Stream 重放未消费消息。

## 7. 范围边界（不做）

- 不替换内存总线为默认（内存仍默认）。
- 不实现消息事务（best-effort）。
- 不引入 Kafka/RabbitMQ 等其他 MQ。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| Redis 版本不满足 Stream | 部署前确认版本，不满足则本需求推迟 |
| sealed interface JSON 反序列化失败 | 用 @JsonTypeInfo 注解 + 单测覆盖全部子类型 |
