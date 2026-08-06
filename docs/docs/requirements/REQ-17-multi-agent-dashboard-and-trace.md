# REQ-17 · Multi-Agent 运行面板 + 结构化 Trace（P1 + P2）

| 字段 | 值 |
|------|-----|
| 来源 | P1（Multi-Agent 运行面板）+ P2（结构化 Trace 系统） |
| 优先级 | P1 |
| 工作量 | 8 人天（P1 5d + P2 3d） |
| 依赖前置 | REQ-10（指标采集）、REQ-01（消息总线，通信可视化数据源） |
| 被依赖 | 无 |
| 关联已有 spec | `platform-completeness-enhancement`（admin Agents/Workflows Tab + NodeEvent SSE 已规划） |
| 里程碑 | Phase 7-C / W9 |

## 1. 功能描述

在 `platform-completeness-enhancement` spec 已规划的 admin `Agents Tab`/`Workflows Tab` + LLM Monitor `Agents 子 Tab` + NodeEvent SSE 轨迹基础上，新增：①Multi-Agent 通信与工具调用实时可视化；②将 NodeEvent 升级为标准化 Span 树（对接 TraceCollector）。

> **调和说明（R4）**：spec 已建面板骨架与 NodeEvent 轨迹。本需求**不另起前端面板**，在 spec 面板上增量"实时通信流 + 工具调用可视化"，并将 NodeEvent 升级为标准 Span。

## 2. 背景与动机

spec 面板是离线/聚合视图，缺实时通信流。生产级需实时观察 Agent 间消息与工具调用。P2 将非标准 NodeEvent 升级为标准 Span，对接可观测体系。

## 3. 用户故事

- **US-1**：作为运维者，我希望实时看到 Agent 间消息流（谁发谁、消息类型），这样能定位协作卡点。
- **US-2**：作为开发者，我希望看到工具调用实时流（哪个 Agent 调了哪个工具、结果），这样能调试工具链路。
- **US-3**：作为 SRE，我希望 Trace 是标准 Span 树（root span + 子 span），这样能对接现有 TraceCollector/Jaeger。

## 4. 功能性验收标准

- **AC-1（实时通信流）**：GIVEN Multi-Agent 运行中；THEN 面板实时展示 Agent 间 AgentMessage 流（sender→receiver、type、timestamp），数据源为 REQ-01 总线。
- **AC-2（工具调用流）**：GIVEN 工具调用；THEN 实时展示 tool name、调用 Agent、结果摘要、耗时。
- **AC-3（Span 树）**：GIVEN 一次协作；THEN 生成 root span（thread）+ 子 span（每 Agent 调用 + 每工具调用），父子关系正确。
- **AC-4（对接 TraceCollector）**：GIVEN Span 树；THEN 经 TraceCollector 写入，可在 TraceCollector 查询。
- **AC-5（复用 spec 面板）**：GIVEN grep 验证；THEN 不新增并行 admin 面板，在 spec Agents/Workflows Tab 增量。
- **AC-6（指标复用）**：GIVEN 面板指标；THEN 读 REQ-10 的 6 指标，不另建数据源（遵循 spec "增量非迁移"）。

## 5. 非功能性要求

- **实时性**：消息/工具流延迟 <2s 展现。
- **依赖**：前端复用 spec admin 应用；后端复用 NodeEvent SSE 通道升级。

## 6. 技术实现要点

- 复用 spec admin Agents/Workflows Tab + NodeEvent SSE。
- 总线（REQ-01）订阅 + SSE 推送通信流。
- NodeEvent 升级为 Span（parentSpanId/spanId/operationName/duration），对接 TraceCollector。
- 前端 Span 树可视化（树或火焰图）。

## 7. 范围边界（不做）

- 不重建 admin 面板骨架（spec 已有）。
- 不引入新 Trace 后端（用 TraceCollector）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| spec admin 面板未落地 | 推动 spec 评审，或本需求含面板基座 |
| 实时流高频推送压垮前端 | 节流 + 批量 SSE |
