# REQ-12 · Scenario 模板机制（M16）

| 字段 | 值 |
|------|-----|
| 来源 | M16（@ScenarioTemplate + DB 存储 + 前端配置） |
| 优先级 | P1 |
| 工作量 | 7 人天（评估修正 +2d，全栈偏紧） |
| 依赖前置 | REQ-11（Factory 提供按 role 装配能力） |
| 被依赖 | 无 |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-C / W10 |

## 1. 功能描述

引入 `@ScenarioTemplate` 机制，将"Agent 组合 + 角色 + 提示词 + 工具白名单"打包为可复用场景模板，存 DB，前端可配置/选择，使不同业务场景（如"数据分析""报表生成""SQL 调优"）一键加载对应 Agent 编排。

## 2. 背景与动机

现状：场景切换需改代码/配置，无模板化能力。M16 提供用户可感知的"场景"抽象，是 v2.0 少数对外可感知特性之一。

## 3. 用户故事

- **US-1**：作为业务用户，我希望从场景列表选择"数据分析"模板，这样系统自动装配对应 Agent 组合，无需理解底层。
- **US-2**：作为管理员，我希望在前端创建/编辑场景模板（选 Agent + 配提示词 + 工具白名单），这样无需开发介入。
- **US-3**：作为 Agent 开发者，我希望用 `@ScenarioTemplate` 标注预置场景，这样开箱即用 + 用户可基于它派生。

## 4. 功能性验收标准

- **AC-1（DB 存储）**：GIVEN `scenario_template` 表（id, name, roles, prompts, toolWhitelist, userId, visibility）；THEN 模板可 CRUD。
- **AC-2（加载）**：GIVEN 用户选某场景；THEN `AgenticAgentFactory` 按模板 roles 装配 Agent + 注入对应提示词 + 工具白名单。
- **AC-3（预置模板）**：GIVEN `@ScenarioTemplate` 标注的内置模板；THEN 启动时落库（如不存在），用户可见。
- **AC-4（前端配置）**：GIVEN 前端场景管理页；THEN 可创建/编辑/删除模板，表单校验 roles 合法。
- **AC-5（隔离）**：GIVEN 私有模板；THEN 仅属主可见（userId 隔离，遵循项目约定）。

## 5. 非功能性要求

- **全栈**：表 + DAO + Service + Controller + 前端页（遵循 BusinessKnowledgeController 模式）。
- **依赖**：REQ-11 Factory。

## 6. 技术实现要点

- `@ScenarioTemplate(name, roles, ...)` 注解 + 启动扫描落库。
- `scenario_template` 表（Flyway 迁移，遵循"版本号唯一"硬约束）。
- Controller `/api/v1/scenarios` CRUD（Sa-Token 鉴权 + userId 隔离）。
- 前端 ScenarioPage（ManagementPage 布局，遵循 platform-completeness-enhancement 前端约定）。

## 7. 范围边界（不做）

- 不实现 LLM 自动生成场景（属 v2.1）。
- 不实现场景版本管理。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 全栈 7d 偏紧 | 前端部分可挪入 REQ-17 前端批次联调 |
| Flyway 版本号冲突 | 确认最新版本号（参考 sandbox V019）后递增 |
