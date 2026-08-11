# REQ-15 · Skill 热加载 + 语义匹配（E1 + E2）

| 字段 | 值 |
|------|-----|
| 来源 | E1（Skill 热加载）+ E2（SkillEmbeddingService 语义匹配） |
| 优先级 | P1 |
| 工作量 | 6 人天（E1 4d + E2 3d，复用 spec Skill 系统 -1d） |
| 依赖前置 | `mcp-multiagent-refactor` spec 的 `SkillCatalogService`/`SkillExecutor`/`Skill` 表 |
| 被依赖 | 无 |
| 关联已有 spec | `mcp-multiagent-refactor`（Skill 系统已规划，本需求为增量） |
| 里程碑 | Phase 7-C / W7 |

## 1. 功能描述

在 `mcp-multiagent-refactor` spec 已规划的 Skill 系统（SkillCatalogService + SkillExecutor + skill 表）基础上，新增：①Skill 热加载（DB/文件/远程 URL 变更自动重载）；②SkillEmbeddingService 语义匹配（替代关键字 findRelevant）。

> **调和说明（R4）**：评估发现计划书 E1/E2 与 spec 的 Skill 系统直接重叠。本需求**禁止重建 Skill PO/Dao/表/Service**，仅在 spec 已有实现上增量。开发前须确认 spec 落地状态。

## 2. 背景与动机

spec 的 Skill 系统是手动 CRUD；本需求补生产级能力：热加载（无需重启生效）+ 语义匹配（比关键字更准）。参考 mewcode SkillCatalog（分析报告"值得借鉴"）。

## 3. 用户故事

- **US-1**：作为管理员，我希望修改 DB 中 Skill 后自动生效，无需重启。
- **US-2**：作为用户，我希望用自然语言描述意图就能匹配到合适 Skill，而非记关键字。
- **US-3**：作为开发者，我希望支持从远程 URL 加载 Skill 定义，这样可集中分发。

## 4. 功能性验收标准

- **AC-1（DB 热加载）**：GIVEN DB 中 Skill 变更；THEN SkillCatalogService 在 <10s 内感知并重载（监听/轮询）。
- **AC-2（文件热加载）**：GIVEN 文件系统 Skill 变更；THEN WatchService 触发重载。
- **AC-3（URL 加载）**：GIVEN 远程 URL Skill 定义；THEN 定时拉取 + 校验 + 加载。
- **AC-4（语义匹配）**：GIVEN SkillEmbeddingService；THEN 输入自然语言意图返回 Top-K 相似 Skill（复用 pgvector）。
- **AC-5（替代关键字）**：GIVEN findRelevant；THEN 改用语义匹配，关键字匹配作为 fallback。
- **AC-6（不重建 spec）**：GIVEN grep 验证；THEN 无重复 Skill PO/Dao/Service 类（复用 spec 已有）。

## 5. 非功能性要求

- **依赖**：复用 pgvector（不引入新向量库）。
- **性能**：热加载 <10s 生效；语义匹配 <100ms。

## 6. 技术实现要点

- 复用 spec 的 `Skill` PO/Dao/`SkillCatalogService`/`SkillExecutor`。
- 新增 `SkillHotReloader`（DB 轮询/事件 + 文件 WatchService + URL 定时拉取）。
- 新增 `SkillEmbeddingService`（Skill 描述向量化 + pgvector 检索）。
- `findRelevant` 改为调 SkillEmbeddingService。

## 7. 范围边界（不做）

- 不重建 Skill CRUD 系统（spec 已有）。
- 不实现 LLM 自动生成 Skill（属 v2.1）。

## 8. 风险与依赖

| 风险 | 缓解 |
|------|------|
| spec 未落地导致本需求阻塞 | 开发前推动 spec 评审落地，或本需求前置包含 spec 的 Skill 基座 |
| 热加载并发冲突 | 重载加锁 + 版本号 |
