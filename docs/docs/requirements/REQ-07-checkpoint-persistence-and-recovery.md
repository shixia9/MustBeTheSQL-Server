# REQ-07 · Checkpoint 持久化与自动恢复（M5 + M11）

| 字段 | 值 |
|------|-----|
| 来源 | M5（Checkpoint 持久化到 MySQL）+ M11（Checkpoint 恢复自动拉起） |
| 优先级 | **P0** |
| 工作量 | 4 人天（采纳框架 MysqlSaver，较原 5d -1d） |
| 依赖前置 | 无 |
| 被依赖 | REQ-03（Redis 总线断点续跑复用 Checkpoint） |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-A / W2，恢复逻辑 W6 |

## 1. 功能描述

采纳框架自带的 `com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver` 将 StateGraph checkpoint 持久化到 MySQL，并实现 `AgenticRunner` 启动时检查未完成 checkpoint、自动恢复 RUNNING 态 / 通知 WAITING_HITL 态恢复。

> **评估修正（R1）**：计划书原拟"自建 JdbcCheckpointSaver + 自定义 agent_checkpoint DDL（JSONB）"。核实框架 1.1.2.0 已内置 MysqlSaver（含 Builder + CreateOption 建表），且 MySQL 无 JSONB 类型（计划书技术错误）。本需求改为**采纳 MysqlSaver**，删除自定义 DDL，工作量 5d→4d。

## 2. 背景与动机

现状：状态纯内存，服务重启会话全丢（计划书 1.1 "恢复率 0%"）。生产级必须支持重启恢复。框架已提供 Saver，无需自建。

## 3. 用户故事

- **US-1**：作为用户，我希望服务重启后我的进行中任务自动恢复，这样不丢失工作进度。
- **US-2**：作为用户，我希望重启后 HITL 等待中的任务被通知恢复，这样我能继续审批。
- **US-3**：作为运维者，我希望 checkpoint 异步写入不阻塞主流程，这样持久化不影响延迟。

## 4. 功能性验收标准

- **AC-1（采纳 Saver）**：GIVEN AgentOrchestrator.compile(config) 时注入 MysqlSaver；THEN checkpoint 写入 MySQL（框架自带表，非自定义 DDL）。
- **AC-2（写入/读取）**：GIVEN 6-Agent 执行中；THEN 每节点完成写 checkpoint；重启后能读出最后 checkpoint。
- **AC-3（RUNNING 恢复）**：GIVEN 服务重启发现 RUNNING 态 checkpoint；WHEN AgenticRunner.start() checkPendingCheckpoints()；THEN 从最后 checkpoint 恢复 StateGraph 继续执行，<3s（计划书 1.1 指标）。
- **AC-4（WAITING_HITL 恢复）**：GIVEN 重启发现 WAITING_HITL；THEN 通知前端恢复 HITL 界面，不自动继续。
- **AC-5（异步写入）**：GIVEN checkpoint 写入；THEN 不阻塞主流程（异步，失败仅日志）。
- **AC-6（大小限制）**：GIVEN checkpoint >1MB；THEN 历史消息截断（计划书 5.1 缓解），保证 <3s 恢复。

## 5. 非功能性要求

- **依赖**：框架 `spring-ai-alibaba-graph-core:1.1.2.0` MysqlSaver（已确认存在）。MySQL 连接复用业务库（计划书 5.3 问题 1 结案：选 MySQL）。
- **性能**：恢复 <3s；checkpoint 异步不阻塞。
- **DDL**：使用 MysqlSaver 的 CreateOption 自动建表，**禁止**自定义 JSONB DDL。

## 6. 技术实现要点

- `AgentOrchestrator.compile(CompileConfig)` 注入 `MysqlSaver.builder().dataSource(...).createOption(CREATE_IF_NOT_EXISTS).build()`。
- `AgenticRunner.start()` 新增 `checkPendingCheckpoints()`：查 RUNNING→恢复、WAITING_HITL→通知。
- checkpoint 大小控制：序列化前裁剪历史消息至 1MB。

## 7. 范围边界（不做）

- 不自建 JdbcCheckpointSaver（用框架 MysqlSaver）。
- 不实现 PostgreSQL Checkpoint（框架支持但本版选 MySQL）。
- 不实现跨实例 checkpoint 共享（REQ-03 Redis 总线范畴）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| MysqlSaver schema 与业务库冲突 | 用独立表前缀 + CreateOption CREATE_IF_NOT_EXISTS |
| 大 checkpoint 恢复慢 | 1MB 截断 + 异步写入 |
| WAITING_HITL 恢复通知前端通道 | 复用现有 SSE/AgentEventSinkRegistry |
