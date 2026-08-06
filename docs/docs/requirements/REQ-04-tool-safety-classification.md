# REQ-04 · 工具安全分级与全量标注（M3 + M13）

| 字段 | 值 |
|------|-----|
| 来源 | M3（ToolSafetyLevel + @ToolMeta）+ M13（现有工具标注） |
| 优先级 | **P0** |
| 工作量 | 4 人天（M3 3d + M13 1d） |
| 依赖前置 | 无 |
| 被依赖 | REQ-05（并行执行按 safety 分区）、REQ-18（RBAC 复用 guard 基座） |
| 关联已有 spec | `mcp-multiagent-refactor`（`ToolDefinition.source` + `ToolInvocationGuard` 同域） |
| 里程碑 | Phase 7-A / W1 |

## 1. 功能描述

引入 `ToolSafetyLevel` 枚举（READ_ONLY / READ_WRITE / MUTATING）与 `@ToolMeta` 注解，为所有工具标注安全级别、是否可并行、是否需确认。**与已有 `ToolDefinition.source`（BUILTIN/MCP/SKILL）合并为统一工具元数据模型**，不另起并行注解体系。

## 2. 背景与动机

现状：[ToolRegistry](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agent/tool/ToolRegistry.java) 无安全级别概念，工具执行串行无区分。`mcp-multiagent-refactor` 已设计 `source`（来源轴）与 `ToolInvocationGuard`（权限轴）。本需求新增 `safety`（安全轴），三者正交，合并进 ToolDefinition，为 REQ-05 并行执行与 REQ-18 RBAC 提供基座。

> **调和说明**：评估发现计划书 M3 与 spec 的 `source`/`guard` 在工具元数据上存在重叠风险。本 REQ 明确：**复用 spec 的 ToolDefinition 作为元数据容器，在其上新增 safety 字段**，禁止新建并行注解。

## 3. 用户故事

- **US-1**：作为工具开发者，我希望用 `@ToolMeta(safety=READ_ONLY, parallelizable=true)` 声明工具属性，这样框架能自动决定并行/串行执行策略。
- **US-2**：作为安全审查者，我希望所有工具有明确安全级别，这样 MUTATING 工具强制需确认、READ_ONLY 可并行加速。
- **US-3**：作为平台管理员，我希望动态注册的 MCP 工具有安全级别推断默认值，这样未知工具不会因"未标注"而绕过安全策略。

## 4. 功能性验收标准

- **AC-1（枚举与注解）**：GIVEN `ToolSafetyLevel{READ_ONLY, READ_WRITE, MUTATING}` 与 `@ToolMeta(name, safety, parallelizable, requiresConfirmation)`；THEN 注解可标注于 Tool 实现类。
- **AC-2（元数据合并）**：GIVEN ToolDefinition；THEN 含 `source`（来自 spec）+ `safety`（本需求新增）+ `parallelizable` + `requiresConfirmation`，无两套并行注解。
- **AC-3（内置工具标注）**：GIVEN sql/schema/python/sample 四个内置工具；THEN 标注为：sql=READ_WRITE/不可并行/需确认、schema=READ_ONLY/可并行、python=READ_WRITE/不可并行、sample=READ_ONLY/可并行。
- **AC-4（MCP 工具标注）**：GIVEN 动态注册的 MCP 工具未显式标注；THEN 默认推断为 READ_WRITE/不可并行（fail-safe，未知视为高风险）；显式标注覆盖默认。
- **AC-5（覆盖率 100%）**：GIVEN `toolRegistry.listTools()`；THEN 每个工具的 safety 字段非 null（计划书 1.1 "覆盖率 100%"指标）。
- **AC-6（与 guard 协同）**：GIVEN MUTATING 工具调用；THEN 经 `ToolInvocationGuard`（spec 已有）+ requiresConfirmation 双重校验。

## 5. 非功能性要求

- **零破坏**：`ToolDefinition` 新增字段不破坏 spec 已规划的构造点（ToolRegistry.registerBuiltins、McpServerManager.connectAndRegister）。
- **fail-safe**：未知工具默认 READ_WRITE，不得默认 READ_ONLY（安全闭合，呼应 sandbox fail-closed 原则）。

## 6. 技术实现要点

- `ToolDefinition` record 新增 `safety`/`parallelizable`/`requiresConfirmation` 字段（与 spec 的 source 共存）。
- `@ToolMeta` 注解由 ToolRegistry 在注册时反射读取并填入 ToolDefinition。
- MCP 工具注册时若无 @ToolMeta，按 READ_WRITE 推断（M13 的"100% 覆盖"机制）。

## 7. 范围边界（不做）

- 不实现并行执行引擎（REQ-05）。
- 不实现 RBAC 权限矩阵（REQ-18，仅复用 guard）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 与 spec 的 source 字段命名/位置冲突 | 开发前确认 spec 落地状态，safety 入 ToolDefinition 而非新注解类 |
| MCP 工具动态注册导致"100% 覆盖"难验证 | 默认推断机制保证非 null，单测断言 listTools 全量非 null |
