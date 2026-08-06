# REQ-14 · Plan DAG 化执行（M19）

| 字段 | 值 |
|------|-----|
| 来源 | M19（Plan DAG 化执行，拓扑排序 + 并行步骤组） |
| 优先级 | P1 |
| 工作量 | 3 人天 |
| 依赖前置 | REQ-05（并行执行分区策略作为基础） |
| 被依赖 | 无 |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-C / W10 |

## 1. 功能描述

将 Plan 的步骤执行从顺序执行升级为 DAG 拓扑排序执行：无依赖的步骤同层并行，有依赖的步骤按拓扑序串行。是计划书 3.3 节"Phase 2 DAG 拓扑排序"的落地。

## 2. 背景与动机

现状：Plan 步骤顺序执行（[WorkflowAgentExecutorImpl](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/workflow/WorkflowAgentExecutorImpl.java)）。REQ-05 已实现 READ_ONLY 工具并行，但 Plan 层步骤仍串行。DAG 化使无依赖步骤（如"查 schema"+"查 sample"）并行，提升端到端延迟。

## 3. 用户故事

- **US-1**：作为 PlannerAgent，我希望生成的 Plan 带步骤依赖关系，这样可拓扑排序。
- **US-2**：作为用户，我希望无依赖步骤并行执行，这样整体延迟降低。
- **US-3**：作为 Agent，我希望 DAG 调度器自动决定并行/串行，这样我不需手动协调。

## 4. 功能性验收标准

- **AC-1（依赖建模）**：GIVEN Plan 步骤含 `dependsOn` 字段；THEN 可构建 DAG。
- **AC-2（拓扑排序）**：GIVEN DAG；THEN 拓扑排序产出层级，同层无依赖。
- **AC-3（同层并行）**：GIVEN 同层 2 步骤；THEN 并行执行，总耗时≈max(单步)。
- **AC-4（依赖串行）**：GIVEN B dependsOn A；THEN A 完成后 B 才执行。
- **AC-5（环检测）**：GIVEN 循环依赖；THEN 检测并报错，不死等。
- **AC-6（与 REQ-05 协同）**：GIVEN DAG 节点内含多工具；THEN 节点内仍按 REQ-05 safety 分区并行。

## 5. 非功能性要求

- **性能**：无依赖步骤并行收益 ≥2x（≥3 步骤时）。
- **资源**：并行步骤受 REQ-08 并发控制约束。

## 6. 技术实现要点

- Plan 步骤增 `dependsOn` 字段。
- DAG 调度器（拓扑排序 + 同层 CompletableFuture 并行，复用 REQ-05 StreamingExecutor 思路）。
- 环检测（Kahn 算法）。

## 7. 范围边界（不做）

- 不实现 AWEL 风格可视化 DAG 编辑器（属 v2.1，计划书"不做清单"）。
- 不实现动态重规划（属 v2.1）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| DAG 调度死锁 | 环检测 + 超时 |
| 并行步骤资源争用 | REQ-08 并发控制兜底 |
