# REQ-09 · AgentMiddleware 生命周期钩子链（M7）

| 字段 | 值 |
|------|-----|
| 来源 | M7（AgentMiddleware 生命周期钩子链） |
| 优先级 | **P0** |
| 工作量 | 4 人天 |
| 依赖前置 | 无 |
| 被依赖 | REQ-02（总线集成挂载钩子，软依赖）、REQ-10（指标经钩子采集） |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-A / W3 |

## 1. 功能描述

引入 `AgentMiddleware` 接口与 8 个生命周期钩子（before_init / after_init / before_act / after_act / before_reply / after_reply / on_error / on_finalize），形成可插拔钩子链，允许插件在关键节点介入（日志、监控、权限、审计），参考 DB-GPT AgentMiddleware 设计（分析报告 5 节）。

## 2. 背景与动机

现状：横切关注点（日志、监控、权限）散落在各 Agent 内部，无统一扩展点。新增 Agent 需复制粘贴这些逻辑。Middleware 模式比继承灵活、比 AOP 语义化（DB-GPT 分析报告"值得借鉴"第 3 点）。

## 3. 用户故事

- **US-1**：作为框架开发者，我希望有统一钩子链，这样横切逻辑（日志/指标/权限）可插件式注入，不污染 Agent 主流程。
- **US-2**：作为 Agent 开发者，我希望 before_act 能修改输入、after_act 能修改输出，这样可在不改 Agent 代码前提下增强行为。
- **US-3**：作为运维者，我希望 Middleware 异常不中断主流程，这样插件 bug 不致 Agent 崩溃。

## 4. 功能性验收标准

- **AC-1（8 钩子）**：GIVEN AgentMiddleware 接口；THEN 含 before_init/after_init/before_act/after_act/before_reply/after_reply/on_error/on_finalize 八个方法。
- **AC-2（触发顺序）**：GIVEN Agent 生命周期；THEN 钩子按 init→act→reply→finalize 顺序触发，error 时触发 on_error。
- **AC-3（可修改）**：GIVEN before_act 修改输入；THEN Agent.act 收到修改后输入；after_act 修改输出则下游收到修改后输出。
- **AC-4（异常隔离）**：GIVEN 某 Middleware 抛异常；THEN 仅记录日志，不中断主流程（计划书 5.1 缓解）。
- **AC-5（链式有序）**：GIVEN 多个 Middleware；THEN 按 @Order 顺序执行。
- **AC-6（注册）**：GIVEN Middleware 标注 @Component；THEN 自动注册进链（Spring 扫描）。

## 5. 非功能性要求

- **性能**：空 Middleware 链对延迟影响 <1ms。
- **隔离**：每个钩子调用 try-catch 包裹。
- **可观测**：钩子执行经 REQ-10 记录（可选）。

## 6. 技术实现要点

- `AgentMiddleware` 接口 + `MiddlewareChain`（@Order 排序）。
- ConversableAgent 在 init/act/reply/finalize 调用点插入 `chain.fireBeforeAct(...)` 等。
- 默认提供 `LoggingMiddleware`、`MetricsMiddleware`（REQ-10 复用）。

## 7. 范围边界（不做）

- 不实现权限 Middleware（属 REQ-18 RBAC）。
- 不实现 AOP 替代（显式钩子调用，非代理）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 钩子链异常中断主流程 | 每钩子 try-catch，异常仅日志 |
| 钩子顺序依赖难调试 | @Order 显式 + 链日志 |
