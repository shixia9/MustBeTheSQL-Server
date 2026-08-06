# REQ-05 · READ_ONLY 工具并行执行（M12）

| 字段 | 值 |
|------|-----|
| 来源 | M12（READ_ONLY 工具并行执行，StreamingExecutor 分区策略） |
| 优先级 | **P0** |
| 工作量 | 3 人天 |
| 依赖前置 | REQ-04（safety 分级，提供分区依据） |
| 被依赖 | REQ-14（Plan DAG 并行复用分区策略） |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-B / W5 |

## 1. 功能描述

引入 `StreamingExecutor` 分区策略：将同一轮需执行的工具按 `ToolSafetyLevel` 分区——READ_ONLY 工具并行执行、READ_WRITE 顺序执行、MUTATING 顺序执行且需确认。参考 mewcode-java 的 StreamingExecutor 并行策略（分析报告 3.4 节）。

## 2. 背景与动机

现状：工具串行执行，多个只读工具（如 schema 查询 + sample 查询）需排队，浪费延迟。REQ-04 提供了 safety 分级，本需求利用它做并行加速。计划书 1.1 指标"并行步骤执行支持"的 Phase 1 实现（Phase 2 DAG 化属 REQ-14）。

## 3. 用户故事

- **US-1**：作为数据分析师，我希望多个只读查询能并行执行，这样一轮分析延迟从 N×t 降到 max(t)。
- **US-2**：作为 Agent，我希望框架自动按 safety 分区调度，这样我无需关心哪些可并行。
- **US-3**：作为 DBA，我希望并行 DB 工具不耗尽连接池，这样并行加速不以牺牲稳定性为代价。

## 4. 功能性验收标准

- **AC-1（分区）**：GIVEN 一轮含 2 个 READ_ONLY + 1 个 READ_WRITE 工具；WHEN 执行；THEN READ_ONLY 2 个并行、READ_WRITE 在 READ_ONLY 完成后顺序执行。
- **AC-2（并行加速）**：GIVEN 3 个各耗时 100ms 的 READ_ONLY 工具；WHEN 并行执行；THEN 总耗时 ≈100ms（非 300ms），容忍调度开销。
- **AC-3（MUTATING 顺序+确认）**：GIVEN 2 个 MUTATING 工具；THEN 顺序执行，每个经 requiresConfirmation 校验。
- **AC-4（连接池保护）**：GIVEN 并行 READ_ONLY DB 工具；THEN 并发 DB 连接数受 `dbSemaphore`（REQ-08）限制，不超连接池上限。
- **AC-5（结果顺序）**：GIVEN 并行工具；THEN 结果按工具调用顺序（非完成顺序）回填到对话，保证可读性。

## 5. 非功能性要求

- **性能**：READ_ONLY 并行收益 ≥2x（≥3 个只读工具时）。
- **资源**：默认连接池扩容至 50（计划书 5.1 缓解措施）；`dbSemaphore` 默认 10 并发（与 LLM 并发一致）。
- **依赖**：依赖 REQ-08 的 `dbSemaphore`（W1 已就位）。

## 6. 技术实现要点

- 新增 `domain/agentic/action/StreamingExecutor.java`（分区 + CompletableFuture 并行）。
- 分区逻辑：`tools.groupBy(safety)` → READ_ONLY 并行流 → READ_WRITE 顺序 → MUTATING 顺序+确认。
- 与 REQ-08 `dbSemaphore` 协同：DB 工具获取 semaphore 后执行。

## 7. 范围边界（不做）

- 不实现 DAG 拓扑排序（属 REQ-14，P1）。
- 不改 MUTATING 工具的确认 UI（复用现有 HITL）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 并行 DB 工具耗尽连接池 | dbSemaphore 限流 + 连接池扩容至 50 |
| 并行结果顺序错乱 | 按调用序号回填，非按完成序 |
