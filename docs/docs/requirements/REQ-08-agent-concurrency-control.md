# REQ-08 · Agent 并发控制（M6）

| 字段 | 值 |
|------|-----|
| 来源 | M6（Semaphore + AgentConcurrencyController） |
| 优先级 | **P0** |
| 工作量 | 2 人天 |
| 依赖前置 | 无 |
| 被依赖 | REQ-05（dbSemaphore 限流并行 DB 工具） |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-A / W1 |

## 1. 功能描述

引入 `AgentConcurrencyController`，基于 `Semaphore` 限制 LLM 调用并发（默认 10，可配）与 DB 工具并发，防止突发流量打爆 LLM 网关或耗尽数据库连接池。

## 2. 背景与动机

现状：LLM 调用无并发限制（计划书 1.1 "无限制"），多用户/多 Agent 并发时易触发 LLM 限流或 DB 连接池耗尽。计划书 1.1 目标"10 并发（可配）"。

## 3. 用户故事

- **US-1**：作为 SRE，我希望 LLM 调用并发受控，这样突发流量不会打爆 LLM 网关配额。
- **US-2**：作为 DBA，我希望 DB 工具并发受控，这样连接池不被 Agent 耗尽。
- **US-3**：作为运维者，我希望并发上限可配置，这样能按实例规格调整。

## 4. 功能性验收标准

- **AC-1（LLM 限流）**：GIVEN `llmSemaphore=10`；WHEN 15 个并发 LLM 调用；THEN 10 个执行、5 个等待，无超配。
- **AC-2（DB 限流）**：GIVEN `dbSemaphore=10`；WHEN 15 个并发 DB 工具调用；THEN 10 个执行、5 个等待。
- **AC-3（可配）**：GIVEN `agent.concurrency.llm=20`；THEN llmSemaphore 上限变 20。
- **AC-4（超时）**：GIVEN 等待超过阈值；THEN 返回超时错误而非死等。
- **AC-5（公平）**：GIVEN Semaphore fair=true；THEN 按请求顺序获取许可，无饥饿。

## 5. 非功能性要求

- **配置**：`agent.concurrency.llm`（默认 10）、`agent.concurrency.db`（默认 10）、`agent.concurrency.timeout-seconds`（默认 30）。
- **可观测**：经 REQ-10 暴露当前占用/等待数。

## 6. 技术实现要点

- 新增 `domain/agentic/core/AgentConcurrencyController.java`（持 llmSemaphore + dbSemaphore，fair Semaphore）。
- ConversableAgent LLM 调用前 `controller.acquireLlm()`，finally `releaseLlm()`。
- StreamingExecutor（REQ-05）DB 工具前 `controller.acquireDb()`。

## 7. 范围边界（不做）

- 不实现 Agent 实例池化（属 v2.1，计划书"不做清单"）。
- 不实现分布式限流（单实例 Semaphore，分布式属 v2.1）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| Semaphore 死锁（异常未释放） | try/finally 严格释放 + 超时 |
| 公平 Semaphore 性能损耗 | 默认非公平，可配 |
