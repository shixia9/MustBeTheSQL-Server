# REQ-11 · AgentFactory + @AgentRole + 动态 Speaker 选择（M14 + M15）

| 字段 | 值 |
|------|-----|
| 来源 | M14（AgentFactory + @AgentRole 注解）+ M15（动态 Speaker 选择） |
| 优先级 | P1 |
| 工作量 | 6 人天（M14 3d + M15 3d） |
| 依赖前置 | 无 |
| 被依赖 | REQ-12（Scenario 模板复用 Factory） |
| 关联已有 spec | 无（命名冲突：现有 `AiAgentFactory`，见下） |
| 里程碑 | Phase 7-C / W9 |

## 1. 功能描述

引入 `@AgentRole` 注解与多 Agent 域的 `AgentFactory`，取代 AgentOrchestrator 构造器中的硬编码 Agent 注入，实现 Agent 的自动发现与按角色装配；并实现 `ManagerAgent.selectNextSpeaker()` 的 LLM 驱动动态选择，替代当前静态路由。

> **评估修正（R5）**：仓库已存在 [AiAgentFactory.java](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agent/core/AiAgentFactory.java)（单 Agent 域 `domain/agent/core/`）。本需求须先决策：①将 AiAgentFactory 泛化为统一工厂；②在 `domain/agentic/` 下新建 `AgenticAgentFactory` 并显式区分职责。**默认选②**，避免触动单 Agent 域已验证逻辑（遵循用户约束"不修改生产已验证依赖"）。

## 2. 背景与动机

现状：[AgentOrchestrator 构造器](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/config/AgentOrchestrator.java#L29) 硬编码注入 6 个 Agent，新增 Agent 需改构造器 + StateGraph。ManagerAgent 路由基于 state 的 NEXT_NODE（偏静态）。计划书 M14/M15 的角色解耦与动态调度。

## 3. 用户故事

- **US-1**：作为 Agent 开发者，我希望用 `@AgentRole(name="DATA_SCIENTIST")` 标注 Agent，这样 Factory 自动发现并装配，无需改 Orchestrator。
- **US-2**：作为场景配置者，我希望按场景选择性启用 Agent，这样不同场景用不同 Agent 组合。
- **US-3**：作为 Agent，我希望 ManagerAgent 由 LLM 动态选择下一个发言者，这样协作更灵活（参考 DB-GPT select_speaker）。

## 4. 功能性验收标准

- **AC-1（注解发现）**：GIVEN Agent 类标注 `@AgentRole`；THEN `AgenticAgentFactory.discover()` 自动收集，无需手动注入。
- **AC-2（装配）**：GIVEN Factory 按场景配置的 role 列表；THEN 装配对应 Agent 进 StateGraph。
- **AC-3（与 AiAgentFactory 隔离）**：GIVEN 单 Agent 域仍用 AiAgentFactory；THEN 互不影响，无循环依赖。
- **AC-4（动态 Speaker）**：GIVEN ManagerAgent.selectNextSpeaker()；THEN 经 LLM 决策返回下一个 Agent 名（参考 DB-GPT select_speaker 模式）。
- **AC-5（fallback）**：GIVEN LLM 决策失败；THEN 退化为 round-robin 或静态路由，不阻塞。

## 5. 非功能性要求

- **零破坏**：单 Agent 域 AiAgentFactory 不动。
- **可配**：场景→role 列表映射可配（支撑 REQ-12）。

## 6. 技术实现要点

- `@AgentRole(name, ...)` 注解 + `AgenticAgentFactory`（Spring `@Component` 扫描收集）。
- `ManagerAgent.selectNextSpeaker()` 调用 LLM 给出候选 Agent 名。
- 决策失败 fallback 静态路由（现有 NEXT_NODE 机制）。

## 7. 范围边界（不做）

- 不实现场景模板存储（属 REQ-12）。
- 不改单 Agent 域工厂。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| LLM Speaker 决策不稳/延迟 | fallback 静态路由 + 决策缓存 |
| 与 AiAgentFactory 命名混淆 | 命名 AgenticAgentFactory + 文档明确边界 |
