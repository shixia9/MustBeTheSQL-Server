# REQ-06 · 分级错误处理与 ConversableAgent 集成（M4 + M10）

| 字段 | 值 |
|------|-----|
| 来源 | M4（ErrorCategory + ErrorClassifier + 4 级重试策略）+ M10（与 ConversableAgent 集成，替换 maxRetryCount=3） |
| 优先级 | **P0** |
| 工作量 | 7 人天（M4 4d + M10 3d） |
| 依赖前置 | 无（M4 独立；M10 软依赖 REQ-09 Middleware 记录错误事件） |
| 被依赖 | REQ-10（agent_error_total 指标依赖分类） |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-A / W2，M10 集成在 W5 |

## 1. 功能描述

引入 `ErrorCategory`（TRANSIENT / RECOVERABLE / DEGRADABLE / FATAL）与 `ErrorClassifier`，按错误类型施以差异化重试策略（指数退避/固定间隔/降级方案/不重试），并集成进 [ConversableAgent.generateReply()](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/core/ConversableAgent.java#L240) 的重试循环，替换当前 `maxRetryCount=3` 统一策略。

## 2. 背景与动机

现状：[ConversableAgent.java:47](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/core/ConversableAgent.java#L47) `maxRetryCount=3`，对所有错误一视同仁——RateLimit 该退避重试却快速重试 3 次失败、SQL 语法错误重试无意义却耗 3 次。计划书 1.1 指标"错误分类准确率 >90%"的载体。

## 3. 用户故事

- **US-1**：作为 Agent，我希望 RateLimit 错误指数退避重试最多 5 次，这样能熬过 LLM 限流。
- **US-2**：作为 Agent，我希望 TableNotFound 错误触发降级（查相似表），而非盲目重试。
- **US-3**：作为用户，我希望 FATAL 错误立即返回友好提示，而不是等 3 次重试超时。
- **US-4**：作为运维者，我希望错误按 category 上报指标，这样能定位哪类错误占主导。

## 4. 功能性验收标准

- **AC-1（分类）**：GIVEN ErrorClassifier；WHEN 输入 RateLimit/Timeout → TRANSIENT、SQLSyntaxError → RECOVERABLE、TableNotFound → DEGRADABLE、AuthError/InvalidRequest → FATAL。
- **AC-2（TRANSIENT 退避）**：GIVEN TRANSIENT 错误；THEN 指数退避（1s/2s/4s/8s/16s）最多 5 次。
- **AC-3（RECOVERABLE 固定）**：GIVEN RECOVERABLE；THEN 固定 1s 间隔最多 3 次。
- **AC-4（DEGRADABLE 降级）**：GIVEN DEGRADABLE；THEN 最多 2 次重试 + 触发降级方案（如相似表替换）。
- **AC-5（FATAL 不重试）**：GIVEN FATAL；THEN 0 次重试，立即返回友好错误。
- **AC-6（集成替换）**：GIVEN ConversableAgent.generateReply()；THEN 不再使用 `maxRetryCount=3`，改用 ErrorClassifier + 策略表；旧字段废弃。
- **AC-7（分类准确率）**：GIVEN 标注错误样本集（≥50 例）；THEN 分类准确率 >90%（计划书 1.1 指标，由 REQ-10 测量脚手架跑）。

## 5. 非功能性要求

- **可扩展**：ErrorClassifier 用策略模式，新增错误类型仅需注册规则，不改 ConversableAgent。
- **可观测**：每次分类经 REQ-10 记录 `agent_error_total{category=...}`。
- **降级安全**：降级方案不得静默执行，需日志记录 + 可选 HITL 确认。

## 6. 技术实现要点

- `ErrorCategory` 枚举 + `ErrorClassifier.classify(Throwable) → ErrorCategory`。
- `RetryPolicy`（每类一个：指数退避/固定/降级/不重试）。
- 改造 [ConversableAgent.java:240](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/core/ConversableAgent.java#L240) retry loop：捕获异常 → classify → 按 policy 重试或降级或抛出。
- 降级方案注册表（如 TableNotFound→SimilarTableResolver）。

## 7. 范围边界（不做）

- 不实现 LLM HA 熔断（属 v2.1）。
- 不改单 Agent 图的错误处理（仅 multi-agent ConversableAgent）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 分类规则误判导致 FATAL 被重试 | 样本集回归测试 + FATAL 默认不重试 |
| 降级方案副作用（误替换表） | 降级结果需可回滚 + 日志审计 |
| 分类准确率无样本集 | REQ-10 测量脚手架同步建样本集 |
