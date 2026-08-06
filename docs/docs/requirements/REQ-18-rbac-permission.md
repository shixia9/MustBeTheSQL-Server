# REQ-18 · RBAC 细粒度权限矩阵（P3）

| 字段 | 值 |
|------|-----|
| 来源 | P3（RBAC 细粒度权限：Agent 使用/数据库访问/工具调用权限矩阵） |
| 优先级 | P1 |
| 工作量 | 4 人天（复用 spec ToolInvocationGuard -1d） |
| 依赖前置 | REQ-04（safety 分级，权限决策依据） |
| 被依赖 | 无 |
| 关联已有 spec | `mcp-multiagent-refactor`（ToolInvocationGuard 工具级 userId 守卫） |
| 里程碑 | Phase 7-C / W9 |

## 1. 功能描述

在 spec 的 `ToolInvocationGuard`（工具级 userId 守卫）基座上，新增**角色维度** RBAC：Agent 使用权限、数据库访问权限、工具调用权限三类矩阵，按角色（如 analyst/admin/viewer）授权。

> **调和说明（R4）**：spec 已有工具级 userId 守卫。本需求**复用 guard 基座**，增量角色维度（RBAC），不重建工具级校验。

## 2. 背景与动机

现状：仅有 Sa-Token 登录 + 工具级 userId 隔离（spec），无角色级权限矩阵。生产级多租户需"谁能用哪个 Agent / 访问哪个库 / 调哪类工具"的细粒度控制。计划书 1.1 "工具调用安全等级覆盖率 100%"的权限侧补充。

## 3. 用户故事

- **US-1**：作为管理员，我希望按角色配置权限矩阵（角色×Agent×数据库×工具类别）。
- **US-2**：作为 analyst 角色，我只能用数据分析类 Agent、访问授权库、调 READ_ONLY 工具，不能调 MUTATING。
- **US-3**：作为管理员，我希望越权调用被拒且有审计日志。

## 4. 功能性验收标准

- **AC-1（角色矩阵）**：GIVEN `role_permission` 配置（角色×Agent×DB×工具 safety）；THEN 可 CRUD 配置。
- **AC-2（Agent 使用权限）**：GIVEN analyst 调用未授权 Agent；THEN 拒绝 + 审计日志。
- **AC-3（数据库访问权限）**：GIVEN analyst 访问未授权 DB；THEN 拒绝。
- **AC-4（工具调用权限）**：GIVEN analyst 调 MUTATING 工具但角色仅允许 READ_ONLY；THEN 经 REQ-04 safety + guard 拒绝。
- **AC-5（复用 guard）**：GIVEN grep 验证；THEN 不重建工具级校验，在 ToolInvocationGuard 增量角色判断。
- **AC-6（与 safety 协同）**：GIVEN 工具调用；THEN 先 safety 分级（REQ-04）→ 再角色权限（本需求）→ 再 userId 隔离（spec）。

## 5. 非功能性要求

- **鉴权**：复用 Sa-Token；角色从 session 取。
- **审计**：越权拒绝记录审计日志。
- **性能**：权限检查 <5ms（缓存角色权限）。

## 6. 技术实现要点

- 复用 spec `ToolInvocationGuard`，新增角色判断分支。
- `role_permission` 表（角色×资源×动作）+ 缓存。
- 权限检查链：safety → role → userId。

## 7. 范围边界（不做）

- 不重建工具级守卫（spec 已有）。
- 不实现组织/部门层级权限（属 v2.1）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 权限矩阵配置复杂 | 提供预置角色模板 |
| 检查性能损耗 | 角色权限缓存 + 失效策略 |
