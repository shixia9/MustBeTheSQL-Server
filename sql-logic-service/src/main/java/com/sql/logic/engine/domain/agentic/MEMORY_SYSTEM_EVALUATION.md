# Multi-Agent 记忆与上下文系统评估技术文档

> 评估范围：Chat 界面 multi-agent 任务派发流程（`domain/agentic`）
> 评估日期：2026-07-30
> 参考实现：`REF/mewcode-java`（coding agent 记忆系统）

---

## 一、总体结论速览

| 功能点 | 实现状态 | 关键问题 |
|--------|----------|----------|
| 1. 多轮对话支持 | 🟡 部分实现 | 后端闭环完整；前端未回传 `conversationId`，`turns` 仅内存态、刷新即丢，点击历史会话不重建 |
| 2. 上下文窗口管理 | 🟢 已实现（运行内）/ 🟡 缺动态 UI | 4 层渐进式压缩 + 跨轮滑动窗口已完备；但前端无压缩过程可视化 |
| 3. 跨会话记忆 | 🔴 未生效（multi-agent 路径） | 三级记忆架构存在但接线断裂：单例 bean 从未 `setIdentity`，`userId==null` 导致读写短路；写入靠 `MemoryExtractorService` 但召回未接入 6-Agent 流程 |

**判定**：上述功能尚未完全实现，需参考 `mewcode-java` 实现完整的 multi-agent 记忆系统。

---

## 二、多轮对话支持评估

### 2.1 后端会话处理逻辑（✅ 完整）

**入口**：[AgenticController.java](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/trigger/http/AgenticController.java#L100-L117)

```java
// L100-104: 解析或创建会话，加载历史
Conversation conversation = conversationContextService.resolveConversation(
        request.getConversationId(), currentUserId, request.getUserInput(), request.getLlmConfigId());
Long conversationId = conversation.getId();
String historySection = conversationContextService.loadHistorySection(conversationId);
```

**会话解析**：[ConversationContextService.java:59-86](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/conversation/ConversationContextService.java#L59-L86)
- 显式 `conversationId` → 直接复用
- `conversationId` 为空 → 回退到该用户 **2 小时内最近更新的会话**（L66-76），保证追问自动归并
- 仍无 → 新建会话

**历史加载**：[ConversationContextService.java:89-126](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/conversation/ConversationContextService.java#L89-L126)
- 从 `conversation_detail` 表读取历史轮次
- 滑动窗口：`MAX_HISTORY_TOKENS=4000`、`MAX_TURNS=12`、`CHARS_PER_TOKEN=2`（粗估）
- 支持 `SUMMARIZE` 策略：命中 `conversation.summary_cache` 时前置"早期对话摘要"

**历史持久化**：[AgenticController.java:281-282](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/trigger/http/AgenticController.java#L281-L282) 每轮完成后 `appendTurn(conversationId, userInput, sql, report)` 写入 `ConversationDetail`。

**数据模型**：[ConversationDetail](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/infrastructure/po/ConversationDetail.java) 仅含 `userInput / sqlOutput / executeResult` 三字段，**不含 Agent 思考过程、工具调用、中间 Observation**——历史重建精度有限。

### 2.2 前端对话状态管理（⚠️ 缺陷）

**核心组件**：[ChatPage.tsx](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL/sql-logic-client/src/pages/ChatPage.tsx)

缺陷一：**请求体未携带 `conversationId`**（[L117-L123](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL/sql-logic-client/src/pages/ChatPage.tsx#L117-L123)）
```js
body: JSON.stringify({
  userInput, connectionId, llmConfigId, autoConfirm, schemaContext: schemaName,
  // ❌ 缺少 conversationId —— 虽然 L26 用 useParams 取了，但从未放入请求
})
```
多轮上下文目前**完全依赖后端 2 小时回退启发式**，一旦超时、多会话并发、切换设备即断裂。

缺陷二：**`turns` 仅 React 内存态**（[L35](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL/sql-logic-client/src/pages/ChatPage.tsx#L35) `useState<TurnData[]>([])`），无 localStorage/IndexedDB 持久化，刷新即丢失。

缺陷三：**点击历史会话不重建对话**——`ChatPage` 无 `useEffect(conversationId)` 去调用 `GET /api/v1/conversations/{id}/details` 回填 `turns`，导致侧栏打开旧会话时聊天区为空（后端有数据却不在前端展示）。

### 2.3 数据流转路径

```
前端 ChatPage
  └─ POST /api/v1/agentic/stream  (❌ 无 conversationId)
       └─ AgenticController.streamAgent
            ├─ resolveConversation(2h 回退)  → conversationId
            ├─ loadHistorySection(conversationId)  → historySection 字符串
            └─ agenticRunner.execute(..., conversationId, historySection)
                 └─ historySection 注入 Agent 初始 system/user prompt
            …执行完成…
            └─ appendTurn(conversationId, ...)  → 持久化到 conversation_detail
```

---

## 三、上下文窗口管理机制评估

系统存在**两个独立**的上下文管理层，分工明确但彼此割裂。

### 3.1 运行内上下文管理（单次 Agent 执行，✅ 优秀）

**核心**：[ContextManager.java](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextManager.java)

**4 层渐进式压缩**（[L30-L33](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextManager.java#L30-L33)，按激进度递增）：

| 层级 | 类 | 触发态 | 策略 | LLM |
|------|----|--------|------|-----|
| L1 | [ObservationMicroCompact](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ObservationMicroCompact.java) | WARNING | 截断旧 TOOL 输出（`maxObservationAgeRounds=5`，截到 200 字） | 否 |
| L2 | [SessionMemoryCompact](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/SessionMemoryCompact.java) | WARNING | 丢弃旧轮次，保留最近 `minKeepRecentRounds=3` 轮 + 满足 `minKeepTokens=10000` | 否 |
| L3 | [FullContextCompression](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/FullContextCompression.java) | ERROR | LLM 结构化摘要（任务/已完成步骤/当前状态/关键数据/错误/下一步）替换旧消息 | 是 |
| L4 | [ReactiveCompact](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ReactiveCompact.java) | 反应式 | 紧急只保留 system + 最近 2 轮 | 否 |

**预算配置**：[ContextBudgetConfig.java](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextBudgetConfig.java#L25-L27)
- `maxContextTokens=120_000`（GPT-4o 默认）
- 阈值：WARNING 70% / ERROR 90% / CRITICAL 95% / OVERFLOW 100%
- `reservedTokens=4096`（输出预留）→ `effectiveBudget = 115,904`

**状态机**：[ContextBudgetTracker.getState()](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextBudgetTracker.java#L52-L62) 基于 `tokenCount/effectiveBudget` 比率。含**熔断器**（`maxCompactFailures=3`，连续失败跳过压缩，[L78-L80](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextBudgetTracker.java#L78-L80)）。

**Token 计数**：[TokenCounter](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/util/TokenCounter.java) JTokkit 优先，字符估算兜底。

**调度**：[ContextManager.manageContext()](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextManager.java#L63-L114) 逐层应用，每层后重算 token 与状态，NORMAL 即停。

### 3.2 跨轮上下文管理（会话级，✅ 已实现）

[ConversationContextService](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/conversation/ConversationContextService.java)（见 2.1）：4000 token / 12 轮滑动窗口 + `summary_cache` 摘要缓存（`SUMMARIZE` 策略）。

### 3.3 缺失项

- ❌ **无压缩过程动态可视化 UI**：前端 `ChatPage` / `AgentExecutionView` 仅展示 Agent 节点执行步骤，未渲染上下文压缩事件（层级触发、token 削减前后对比、被压缩内容预览）。后端压缩仅 `log.info`，无 SSE 事件下发。
- ⚠️ 运行内（120K）与跨轮（4K）两套预算未统一治理，存在配置漂移风险。

---

## 四、跨会话记忆功能评估（🔴 核心问题）

### 4.1 三级记忆架构（领域模型存在）

[HybridAgentMemory](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/HybridAgentMemory.java) 设计了写入级联（[L96-L136](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/HybridAgentMemory.java#L96-L136)）：

```
write(fragment)
  → SensoryMemory (buffer=3, 阈值 0.1)        感觉缓冲
    → 溢出 → AgentShortTermMemory (buffer=5, Jaccard 去重 0.85)  近期上下文
      → 溢出 → LLMInsightExtractor → AgentLongTermMemory.writeBatch()  持久化
```

| 层级 | 类 | 介质 | 跨会话 | 生命周期 |
|------|----|------|--------|----------|
| 感觉 | [SensoryMemory](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/SensoryMemory.java) | 内存 | ❌ | buffer=3 |
| 短期 | [AgentShortTermMemory](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/AgentShortTermMemory.java) | 内存 | ❌ | buffer=5 |
| 长期 | [AgentLongTermMemory](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/AgentLongTermMemory.java) | pgvector | ✅（设计） | 永久 |

### 4.2 致命缺陷：接线断裂，长记记忆实际未生效

**缺陷一：单例 bean 从未设置身份**

[AgenticAutoConfiguration.agentMemory()](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/config/AgenticAutoConfiguration.java#L57-L64)：
```java
@Bean @Primary
public AgentMemory agentMemory(...) {
    HybridAgentMemory memory = new HybridAgentMemory(memoryDomainService);  // 无 userId 构造
    memory.setImportanceScorer(importanceScorer);
    memory.setInsightExtractor(insightExtractor);
    return memory;  // ❌ 全程未调用 setIdentity(userId, agentId)
}
```
全局 grep 确认：**`setIdentity` 无任何外部调用者**（仅定义于 `HybridAgentMemory:78` 与 `AgentLongTermMemory:38`）。

**缺陷二：`userId==null` 导致读写双重短路**

- 读：[AgentLongTermMemory.fetchMemories()](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/AgentLongTermMemory.java#L46-L49) `if (... userId == null ...) return List.of();`
- 写：[AgentLongTermMemory.writeBatch()](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/AgentLongTermMemory.java#L77-L80) `if (... userId == null) return;`

→ 三级级联的长期记忆**读写均为空操作**。

**缺陷三：multi-agent 召回路径未接入**

multi-agent Agent 基类 [ConversableAgent.loadThinkingMessages():366](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/core/ConversableAgent.java#L366) 调用 `memory.read(observation)` → `HybridAgentMemory.read()` → `fetchMemories`（因 userId=null 返回空）。

唯一带真实 userId 的召回节点 [MemoryRecallNode](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agent/node/MemoryRecallNode.java#L49) **仅注册于单 Agent 图** [SqlAgentGraphConfiguration:249](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agent/config/SqlAgentGraphConfiguration.java#L249)，**不在 6-Agent `AgentOrchestrator` 流程中**。

**缺陷四：单例共享导致跨用户污染**

`agentMemory` 是 Spring 单例，注入到所有 6 个 Agent（[L250, L268, L288...](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/config/AgenticAutoConfiguration.java#L250)），即便设置身份也会在并发请求间串号。

### 4.3 现有写入路径（部分可用，但召回断链）

[MemoryExtractorService.extractAndPersistAsync()](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/memory/MemoryExtractorService.java#L39) 在每次 agentic 执行后由 [AgenticController:317](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/trigger/http/AgenticController.java#L317) 触发，用 LLM 从对话抽取记忆并 `saveMemories(userId, ...)` 写入 pgvector。

**结论**：写入（带真实 userId）发生，但 multi-agent 流程**从不召回**——记忆"只进不出"，跨会话记忆在 Chat 界面**实际未生效**。

### 4.4 与 mewcode-java 的差距对照

| 能力 | mewcode-java | 本项目 |
|------|--------------|--------|
| 记忆存储 | `.md` 文件 + `MEMORY.md` 索引（[MemoryManager](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryManager.java)） | pgvector（基础设施更优） |
| 召回注入 | [MemoryRecall](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryRecall.java) selector LLM 选 Top-K，`injectLongTermMemory` 注入 system-reminder | ❌ 未接入 multi-agent |
| 后台巩固 | [MemoryConsolidator](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryConsolidator.java) 时间+会话门控，子 Agent 合并去重 | ❌ 无 |
| 记忆年龄 | [MemoryAge](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryAge.java) 衰减告警 | ❌ 无 |
| 上下文压缩 | [ContextCompactor](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/compact/ContextCompactor.java) 摘要旧消息 + `RecoveryState` 快照 | ✅ 已有等价实现（4 层） |
| 工具结果预算 | [ToolResultBudget](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/toolresult/ToolResultBudget.java) 溢写磁盘 | ❌ 无（L1 截断替代） |

---

## 五、潜在问题清单

1. **前端不传 `conversationId`**：多轮对话靠 2h 启发式，多会话并发/超时即错乱。
2. **前端 `turns` 无持久化**：刷新丢失；历史会话点击不回填。
3. **`ConversationDetail` 字段过窄**：丢失 Agent 推理/工具链，重建上下文精度低。
4. **`HybridAgentMemory` 单例 + 无身份**：长记忆读写空转；并发跨用户污染。
5. **召回未接入 6-Agent**：`MemoryRecallNode` 仅单 Agent 图；`ConversableAgent.memory.read()` 因 userId=null 失效。
6. **无记忆巩固/衰减**：长期记忆无限增长，无去重、无过期。
7. **压缩无前端可视化**：用户要求"动态效果 UI 展示压缩过程"未实现。
8. **两套上下文预算未统一**：运行内 120K 与跨轮 4K 配置漂移。

---

## 六、改进建议（实现路线）

### 阶段 A：多轮对话闭环
- 前端 `ChatPage` 请求体加 `conversationId`；从 SSE `COMPLETED` 事件回读 `conversationId`（后端 [L216-L218](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/trigger/http/AgenticController.java#L216-L218) 已下发）。
- `conversationId` 变化时 `GET /conversations/{id}/details` 回填 `turns`；localStorage 兜底。
- 扩展 `ConversationDetail` 或新增 `agent_step` 持久化，保留工具链。

### 阶段 B：跨会话记忆生效
- `agentMemory` 由单例改为**请求级工厂**：每个请求 `new HybridAgentMemory(...)` 并 `setIdentity(userId, agentId)`，注入执行上下文而非 bean 字段。
- 在 6-Agent 流程前置**记忆召回**：参考 mewcode `MemoryRecall`，用 selector LLM 从 pgvector 取 Top-K 注入 system prompt。
- 引入 `MemoryConsolidator` 后台巩固（去重/合并/过期），`MemoryAge` 衰减。

### 阶段 C：压缩动态 UI
- `ContextManager` 触发压缩时经 SSE 下发 `CONTEXT_COMPACT` 事件（层级、前后 token、被压缩轮次预览）。
- 前端新增压缩可视化组件（进度条 + token 削减动画 + 层级标识）。

### 阶段 D：一致性保障
- 统一上下文预算治理；记忆/历史/压缩事件埋点对齐；并发与事务边界测试。

---

## 七、参考文件索引

**本项目**
- 会话：[AgenticController](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/trigger/http/AgenticController.java)、[ConversationContextService](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/conversation/ConversationContextService.java)、[ConversationAppService](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/application/service/ConversationAppService.java)
- 上下文：[ContextManager](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextManager.java)、[ContextBudgetConfig](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/ContextBudgetConfig.java)、[FullContextCompression](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/context/FullContextCompression.java)
- 记忆：[HybridAgentMemory](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/HybridAgentMemory.java)、[AgentLongTermMemory](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/memory/AgentLongTermMemory.java)、[AgenticAutoConfiguration](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/agentic/config/AgenticAutoConfiguration.java)、[MemoryExtractorService](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL-Server/sql-logic-service/src/main/java/com/sql/logic/engine/domain/memory/MemoryExtractorService.java)
- 前端：[ChatPage.tsx](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/MustBeTheSQL/sql-logic-client/src/pages/ChatPage.tsx)

**参考实现 mewcode-java**
- [MemoryManager](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryManager.java)、[MemoryRecall](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryRecall.java)、[MemoryConsolidator](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryConsolidator.java)、[MemoryAge](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/memory/MemoryAge.java)、[ContextCompactor](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/compact/ContextCompactor.java)、[RecoveryState](file:///Users/vamos/Documents/dev/others/SQL-Logic-Engine/REF/mewcode-java/src/main/java/com/mewcode/compact/RecoveryState.java)
