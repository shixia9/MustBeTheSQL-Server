# REQ-20 · Agent 集成测试框架（录制-回放）（Q1）

| 字段 | 值 |
|------|-----|
| 来源 | Q1（@AgentTest + LLM 录制-回放 + 行为断言），去测量脚手架基座（已前置为 REQ-10） |
| 优先级 | P1 |
| 工作量 | 5 人天（基座已前置 REQ-10，本需求为完整框架 -3d） |
| 依赖前置 | REQ-10（测量脚手架基座） |
| 被依赖 | 无 |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-C / W10 |

## 1. 功能描述

实现 Agent 集成测试框架：`@AgentTest` 注解 + LLM 响应录制-回放（cassette）+ 行为断言 DSL，使 Multi-Agent 协作可离线回归测试（不消耗真实 LLM 配额）。

> **评估修正（R3）**：计划书 Q1 原 5d 含测量脚手架。本需求将"测量脚手架基座"前置为 REQ-10（P0），Q1 聚焦完整录制-回放框架 + 行为断言，工作量维持 5d（基座已前置，但完整 cassette 机制 + 断言 DSL 仍需投入，评估认为原 5d 偏紧，前置后 5d 用于上层框架更合理）。

## 2. 背景与动机

现状：无 Agent 集成测试框架，回归靠手工/真实 LLM 调用（贵且不稳）。生产级需可重放的回归基线。计划书 5.3 问题 4 提数据隐私顾虑——用脱敏 fixture。

## 3. 用户故事

- **US-1**：作为 Agent 开发者，我希望用 `@AgentTest` 写集成测试，录制首次 LLM 响应，后续回放，这样回归测试不耗配额。
- **US-2**：作为开发者，我希望用断言 DSL 验证 Agent 行为（"ManagerAgent 应路由到 DATA_SCIENTIST"），而非只看最终输出。
- **US-3**：作为安全审查者，我希望 cassette 数据脱敏，无隐私泄露。

## 4. 功能性验收标准

- **AC-1（@AgentTest）**：GIVEN 测试标注 `@AgentTest`；THEN 框架自动注入录制-回放 LLM 客户端。
- **AC-2（录制）**：GIVEN 首次运行（record 模式）；THEN LLM 响应存为 cassette（脱敏）。
- **AC-3（回放）**：GIVEN 后续运行（replay 模式）；THEN LLM 调用从 cassette 返回，不调真实 API。
- **AC-4（行为断言）**：GIVEN 断言 DSL；THEN 可断言消息流（"A 发给 B"）、工具调用（"调了 sql 工具"）、路由（"经过 MANAGER"）。
- **AC-5（脱敏）**：GIVEN cassette；THEN 敏感数据（PII/凭证）脱敏。
- **AC-6（基于 REQ-10）**：GIVEN 框架；THEN 复用 REQ-10 测量脚手架的用例集与指标采集。

## 5. 非功能性要求

- **依赖**：不引入新测试依赖（用 JUnit5 + 现有 Mockito）。
- **隐私**：cassette 脱敏 + 不入库公开。
- **可维护**：cassette 版本化，LLM prompt 变更可重录。

## 6. 技术实现要点

- `@AgentTest` 注解 + JUnit5 extension。
- `RecordingLlmClient`（record/replay 双模式，wrap 真实 LlmClient）。
- cassette 格式（JSON，脱敏）。
- 断言 DSL（基于 REQ-01 AgentMessage 流 + REQ-10 指标）。

## 7. 范围边界（不做）

- 不重做测量脚手架（REQ-10 已有）。
- 不实现 LLM 响应模拟生成（仅录制-回放）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| cassette 与 prompt 变更失配 | 版本号 + 失配提示重录 |
| 脱敏不彻底泄露隐私 | 审查 cassette 样本 + 自动化 PII 扫描 |
| 回放与真实行为漂移 | 关键路径仍需周期性真实 LLM 验证 |
