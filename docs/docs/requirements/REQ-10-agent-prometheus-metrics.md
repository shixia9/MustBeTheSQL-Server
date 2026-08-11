# REQ-10 · Agent 级 Prometheus 指标 + 测量脚手架（M8 + Q1 基座）

| 字段 | 值 |
|------|-----|
| 来源 | M8（Agent 级 Prometheus 指标）+ Q1 测量脚手架基座（前置拆出） |
| 优先级 | **P0** |
| 工作量 | 4 人天（M8 2d + 测量脚手架 2d，评估修正 R3 前置） |
| 依赖前置 | REQ-09（Middleware 挂载指标采集） |
| 被依赖 | REQ-17（面板复用指标）、REQ-20（测试框架复用脚手架） |
| 关联已有 spec | `platform-completeness-enhancement`（admin Agents Tab 复用本指标） |
| 里程碑 | Phase 7-B / W4 |

## 1. 功能描述

实现 6 个核心 Prometheus 指标（agent_calls_total / agent_latency_seconds / agent_success_rate / agent_token_usage / agent_tool_calls_total / agent_error_total），经 Micrometer 暴露到 `/actuator/prometheus`。**额外前置**：提供一个轻量"测量脚手架"，使 P0 里程碑能在无 Q1 完整测试框架的情况下客观测量"协作成功率""错误分类准确率"等指标。

> **评估修正（R3）**：计划书 Q1 排在 W9，P0 里程碑（W6）时无测量手段，形成"先交付后验收"循环。本需求将 Q1 的"测量脚手架基座"前置为 P0，使 P0 指标可客观验收。

## 2. 背景与动机

现状：无 Agent 级指标（计划书 1.1 "0 指标"）。生产级必须可观测。同时 P0 里程碑的成功指标（>95% 协作成功率、>90% 错误分类准确率）需要测量工具，否则"P0 完成"无客观依据。

## 3. 用户故事

- **US-1**：作为 SRE，我希望 Grafana 能看到 Agent 调用量/延迟/成功率，这样能监控健康度。
- **US-2**：作为发布经理，我希望 P0 里程碑能跑出"协作成功率"客观数值，这样验收不靠主观判断。
- **US-3**：作为成本负责人，我希望看到 token 用量按 Agent 维度分布，这样能优化成本。

## 4. 功能性验收标准

- **AC-1（6 指标暴露）**：GIVEN 应用启动；THEN `/actuator/prometheus` 含 6 个指标，按 agentName 标签维度。
- **AC-2（采集点）**：GIVEN Agent 调用；THEN 经 REQ-09 MetricsMiddleware 记录 calls_total(+1)、latency(耗时)、success/failure、tokenUsage、toolCalls、error（按 REQ-06 category）。
- **AC-3（测量脚手架）**：GIVEN 脚手架 `CollaborationMetricsRunner`；WHEN 跑一组端到端用例；THEN 输出协作成功率（成功 thread 数 / 总 thread 数）、平均延迟、错误分类分布。
- **AC-4（错误分类准确率）**：GIVEN 标注错误样本集；THEN 脚手架跑出 ErrorClassifier 准确率（正确分类数 / 总样本数），供 REQ-06 AC-7 验收。
- **AC-5（admin 复用）**：GIVEN platform-completeness-enhancement 的 admin Agents Tab；THEN 读本指标展示（不另建数据源，符合 spec "增量非迁移"原则）。

## 5. 非功能性要求

- **依赖**：Micrometer + Actuator（Spring Boot 自带，不引入新依赖，遵循用户约束"不替换生产已验证依赖"）。
- **性能**：指标采集异步，对主流程延迟 <1ms。
- **标签基数**：agentName 限定已知 6 Agent + unknown 兜底，避免高基数。

## 6. 技术实现要点

- `MetricsMiddleware implements AgentMiddleware`（REQ-09）注入 MeterRegistry。
- 6 指标用 Micrometer Counter/Timer/Gauge。
- `CollaborationMetricsRunner`（测试工具，非生产代码）：跑用例集 + 聚合输出。
- 复用 `LlmCallReporter` 的 token 数据（不重复采集，遵循 platform-completeness-enhancement "不迁移数据源"约定）。

## 7. 范围边界（不做）

- 不实现完整 LLM 录制-回放（属 REQ-20）。
- 不实现 Grafana Dashboard 模板（运维侧）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 指标标签高基数 | agentName 限定枚举 + unknown 兜底 |
| 测量脚手架用例集不全 | P0 期先覆盖核心 6-Agent 协作路径，逐步扩充 |
| MetricsMiddleware 未就位 | REQ-09 在 W3 先完成 |
