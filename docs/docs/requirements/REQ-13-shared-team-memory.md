# REQ-13 · 共享团队记忆（M18）

| 字段 | 值 |
|------|-----|
| 来源 | M18（TeamAwareHybridMemory + 向量索引扩展） |
| 优先级 | P1 |
| 工作量 | 4 人天 |
| 依赖前置 | 无（现有 HybridAgentMemory 为基座） |
| 被依赖 | 无 |
| 关联已有 spec | 无 |
| 里程碑 | Phase 7-C / W10 |

## 1. 功能描述

在现有 [HybridAgentMemory](../../MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/HybridAgentMemory.java) 基础上，引入 `TeamAwareHybridMemory`，使团队内 Agent 共享记忆（计划、洞察、关键事实），经向量索引扩展实现跨 Agent 语义检索。参考 DB-GPT GptsMemory 共享记忆（分析报告 3.3 节）。

## 2. 背景与动机

现状：记忆按 Agent 隔离，团队成员重复检索相同事实。DB-GPT 的 GptsMemory 提供跨对话计划/消息共享（分析报告"值得借鉴"第 2 点）。共享记忆减少冗余 LLM 调用、提升团队一致性。

## 3. 用户故事

- **US-1**：作为团队成员 Agent，我希望读到其他成员写入团队记忆的关键事实，这样不重复检索。
- **US-2**：作为 ManagerAgent，我希望团队记忆里有统一计划与洞察，这样调度决策有全局视角。
- **US-3**：作为用户，我希望团队记忆可语义检索，这样能按意图而非关键字找到相关历史。

## 4. 功能性验收标准

- **AC-1（共享读写）**：GIVEN Agent A 写入团队记忆；THEN Agent B 可读（同 thread/team 作用域）。
- **AC-2（向量检索）**：GIVEN 团队记忆经向量索引；THEN 支持语义相似度检索（复用 pgvector）。
- **AC-3（隔离）**：GIVEN 不同团队；THEN 记忆互不可见（team 作用域隔离）。
- **AC-4（与短期记忆区分）**：GIVEN Agent 私有短期记忆 + 团队共享记忆；THEN 二者分离，私有记忆不泄露给队友。
- **AC-5（重要性筛选）**：GIVEN 仅高重要性记忆进入团队共享；THEN 低重要性记忆留在私有短期（参考 DB-GPT ImportanceScorer）。

## 5. 非功能性要求

- **依赖**：复用现有 pgvector（PostgreSQL），不引入新向量库（遵循"不加新依赖"约束）。
- **性能**：团队记忆写入异步，不阻塞 Agent 主流程。

## 6. 技术实现要点

- `TeamAwareHybridMemory extends/implements HybridAgentMemory`，新增 team 作用域读写。
- 团队记忆表 + pgvector 索引扩展（复用现有向量基础设施）。
- 重要性评分复用现有 LLM 评分机制。

## 7. 范围边界（不做）

- 不实现跨团队/跨用户记忆共享（隐私）。
- 不引入新向量库。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 共享记忆并发写冲突 | 写入串行化 + 版本号 |
| 向量检索延迟 | 异步写入 + 缓存热点记忆 |
