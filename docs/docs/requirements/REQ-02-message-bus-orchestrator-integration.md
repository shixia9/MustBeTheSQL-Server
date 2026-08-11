# REQ-02 · 消息总线与 AgentOrchestrator 集成（M9，拆 a/b）

| 字段 | 值 |
|------|-----|
| 来源 | M9（消息总线与 AgentOrchestrator 集成） |
| 优先级 | **P0** |
| 工作量 | 7 人天（拆 M9a 旁路 3d + M9b 切换 3d + 收尾 1d，较原 5d +2d 缓冲） |
| 依赖前置 | REQ-01（协议+总线）；REQ-09（Middleware，软依赖——钩子挂载点） |
| 被依赖 | REQ-03（Redis 总线复用集成模式） |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-B / W4-W5，**P0 关键路径最高风险项** |

## 1. 功能描述

将 [AgentOrchestrator](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/config/AgentOrchestrator.java) 的 6-Agent StateGraph 通信从"共享 OverAllState 读写"改造为"经 InMemoryMessageBus 收发 AgentMessage"，且**零行为回归**。采用**先旁路后切换**两阶段策略降低风险。

## 2. 背景与动机

现状：ManagerAgent 经 `state.value(NEXT_NODE)` 路由，worker 经 state 写回结果，是"共享内存耦合"（计划书 3.1 节）。REQ-01 提供了总线，但总线不接入编排器则形同虚设。此需求是全计划风险最高项：破坏现有 6-Agent 协作即等于全盘回归。

## 3. 用户故事

- **US-1**：作为平台可靠性工程师，我希望 Agent 通信经消息总线而非共享 state，这样通信链路可观测、可重放、未来可换 Redis（REQ-03）。
- **US-2**：作为发布经理，我希望总线接入采用"旁路并行验证→切换"两阶段，这样切换失败可回退，不阻断 P0 里程碑。
- **US-3**：作为 Agent 开发者，我希望 ManagerAgent 的 `act()` 用 `bus.send(TaskDispatch)` 替代 state 写，worker 用 `bus.send(ToolResult)` 替代 state 写回，这样职责单一。

## 4. 功能性验收标准

### M9a — 旁路并行（W4）
- **AC-1（旁路运行）**：GIVEN 消息总线与 StateGraph 并行运行（双写：state 写 + bus.send）；WHEN 跑 6-Agent 端到端用例；THEN 总线消息与 state 变更一致（断言等价），且 StateGraph 行为不变。
- **AC-2（回退能力）**：GIVEN 旁路模式；WHEN 关闭总线开关；THEN 系统回退为纯 state 通信，零影响。

### M9b — 切换（W5）
- **AC-3（主路径切换）**：GIVEN 旁路验证通过；WHEN ManagerAgent/worker 的 state 读写改为总线收发（state 仅保留图执行必需的 NEXT_NODE）；THEN 6-Agent 端到端用例全部通过。
- **AC-4（零回归）**：GIVEN 现有 agentic 集成测试基线；THEN 切换后全部用例绿（无新增失败）。
- **AC-5（NEXT_NODE 保留）**：GIVEN StateGraph 条件边仍需 NEXT_NODE 路由；THEN NEXT_NODE 经 state 保留（图执行机制不变），仅 Agent 业务消息走总线。

### P0 里程碑判定线
- **AC-6**：M9a 旁路验证通过即达 P0 里程碑判定线；M9b 切换可在 W6 缓冲期完成或显式推迟到 W11，不阻断 P0 验收。

## 5. 非功能性要求

- **回归**：`mvn -pl sql-logic-service test` 全绿；新增"旁路等价性"集成测试。
- **性能**：切换后端到端延迟不劣化（与共享 state 相比增加 <5ms）。
- **可观测**：总线消息经 REQ-09 Middleware 钩子记录日志/指标。

## 6. 技术实现要点

- 改造 `ManagerAgent.act()`：`state.write(NEXT_NODE)` 保留（图路由用），新增 `bus.send(TaskDispatch{target, goal})`。
- 改造 worker Agent `act()`：业务结果 `bus.send(ToolResult)`，state 写回精简为图必需字段。
- 引入 `bus-orc.enabled` 配置开关（旁路/切换/关闭三态）。
- 新增 `MessageBusParityIT` 集成测试：双写模式下断言 bus 消息 ↔ state 一致。

## 7. 范围边界（不做）

- 不引入 Redis 总线（REQ-03）。
- 不改 StateGraph 编译结构（节点/边不变，仅改节点内通信）。
- 不改单 Agent 图路径（`domain/agent/`）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 切换破坏 6-Agent 协作（最高风险） | 旁路先行 + 等价性测试 + 配置开关回退；P0 判定线放宽为旁路通过 |
| state 与总线双写数据不一致 | M9a 旁路期断言一致性，不一致即阻塞切换 |
| Middleware 未就位（REQ-09 软依赖） | W3 完成 REQ-09；若延期，总线自带最小日志降级 |
