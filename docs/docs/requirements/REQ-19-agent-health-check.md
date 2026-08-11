# REQ-19 · Agent 健康检查（P4）

| 字段 | 值 |
|------|-----|
| 来源 | P4（Agent 健康检查：LLM 连通性 + Python 沙箱 + DB + 综合状态） |
| 优先级 | P1 |
| 工作量 | 2 人天 |
| 依赖前置 | 无 |
| 被依赖 | 无（可被 REQ-17 面板展示） |
| 关联已有 spec | 无（沙箱健康复用 sandbox-execution-production-upgrade 已有 RuntimeFactory.isCliAvailable） |
| 里程碑 | Phase 7-C / W7 |

## 1. 功能描述

提供 `HealthIndicator` 体系，检查 LLM 连通性、Python 沙箱可用性、DB 连通性，综合得出 Agent 子系统健康状态，暴露到 `/actuator/health`。

## 2. 背景与动机

现状：无 Agent 子系统健康检查，故障不可见。生产级需快速定位"LLM 不通 / 沙箱挂了 / DB 连不上"。

## 3. 用户故事

- **US-1**：作为 SRE，我希望 `/actuator/health` 显示 Agent 子系统状态，这样能接入监控告警。
- **US-2**：作为 SRE，我希望区分 LLM/沙箱/DB 哪个组件不健康，这样快速定位。
- **US-3**：作为运维者，我希望健康检查不耗资源，这样高频探测无副作用。

## 4. 功能性验收标准

- **AC-1（LLM 连通性）**：GIVEN LLM HealthIndicator；THEN 探测 LLM 端点可达性（轻量 ping，非真实推理）。
- **AC-2（沙箱可用性）**：GIVEN 沙箱 HealthIndicator；THEN 探测 Docker/Podman/Nerdctl CLI 可用性（复用 RuntimeFactory.isCliAvailable）+ Local runtime 状态。
- **AC-3（DB 连通性）**：GIVEN DB HealthIndicator；THEN 探测业务库 + pgvector 连通性。
- **AC-4（综合状态）**：GIVEN /actuator/health；THEN 含 agent 子节，状态为各组件聚合（任一 DOWN 则 agent DOWN）。
- **AC-5（轻量）**：GIVEN 健康检查；THEN 单次探测 <1s，不产生真实推理/沙箱执行成本。

## 5. 非功能性要求

- **依赖**：复用 Spring Boot Actuator HealthIndicator（不引入新依赖）。
- **性能**：探测轻量、可配间隔。
- **fail-closed**：沙箱检查遵循 sandbox fail-closed 原则（项目硬约束）。

## 6. 技术实现要点

- `LlmHealthIndicator` / `SandboxHealthIndicator` / `DatabaseHealthIndicator` / `AgentHealthIndicator`（聚合）。
- 沙箱检查复用 `RuntimeFactory`（不实启容器，仅查 CLI 可用性 + 状态）。
- LLM 检查走轻量 `/models` 或 ping 端点。

## 7. 范围边界（不做）

- 不做深度 LLM 推理探活（成本高）。
- 不做沙箱容器实启探活（用 CLI 可用性）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 健康检查误报 | 探测超时 + 重试 |
| 沙箱检查触发实启 | 仅查 isCliAvailable，不调 connect |
