# REQ-16 · MCP Server 注册中心管理（E3）

| 字段 | 值 |
|------|-----|
| 来源 | E3（MCP Server 注册中心，多 Server 动态注册/注销） |
| 优先级 | P1 |
| 工作量 | 2 人天（复用 spec McpServerManager -1d） |
| 依赖前置 | `mcp-multiagent-refactor` spec 的 `McpServerManager`（connect/disconnect/reconnect/精确路由） |
| 被依赖 | 无 |
| 关联已有 spec | `mcp-multiagent-refactor`（McpServerManager 已实现注册/注销/重连） |
| 里程碑 | Phase 7-C / W7 |

## 1. 功能描述

在 spec 已规划的 `McpServerManager`（connectAndRegister / disconnect 反注册 / @PostConstruct reconnectAll / 精确路由）基础上，新增"多 Server 动态注册/注销管理"接口与状态面板，使运维可在线增删 MCP Server。

> **调和说明（R4）**：spec 已实现注册中心核心能力。本需求**不重建 McpServerManager**，仅补动态管理 API + 可视化。

## 2. 背景与动机

spec 的 McpServerManager 已具备生命周期与精确路由，但缺少"运行时动态增删 Server + 状态可视化"。E3 收敛为此增量。

## 3. 用户故事

- **US-1**：作为运维者，我希望在线注册新 MCP Server 而不重启服务。
- **US-2**：作为运维者，我希望看到所有 MCP Server 连接状态、工具数、健康度。
- **US-3**：作为运维者，我希望注销 Server 时其工具自动反注册。

## 4. 功能性验收标准

- **AC-1（动态注册）**：GIVEN POST 注册新 Server；THEN connectAndRegister 成功，工具进 ToolRegistry（带 serverId/userId）。
- **AC-2（动态注销）**：GIVEN DELETE 注销 Server；THEN disconnect 反注册其全部工具，后续调用返回 not found。
- **AC-3（状态查询）**：GIVEN GET 状态；THEN 返回各 Server 连接状态/工具数/最后心跳。
- **AC-4（不重建 spec）**：GIVEN grep 验证；THEN 无重复 McpServerManager 类（复用 spec）。
- **AC-5（用户隔离）**：GIVEN 用户 A 注册的 Server；THEN 仅 A 可管理（复用 spec userId 隔离）。

## 5. 非功能性要求

- **鉴权**：Sa-Token + userId（复用 spec）。
- **审计**：注册/注销操作记录日志。

## 6. 技术实现要点

- 复用 spec `McpServerManager`。
- 新增 `McpServerRegistryController`（注册/注销/状态 API）。
- 状态面板并入 REQ-17 Multi-Agent 面板或 admin。

## 7. 范围边界（不做）

- 不重建 MCP 连接/路由（spec 已有）。
- 不实现 MCP 工具市场（属 v2.1）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| spec 未落地 | 推动 spec 评审，或本需求前置含 spec 基座 |
| 动态注册并发 | connect 加锁（参考 sandbox per-session lock 模式） |
