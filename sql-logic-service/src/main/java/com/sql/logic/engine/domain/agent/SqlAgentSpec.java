package com.sql.logic.engine.domain.agent;

/**
 * Central specification for the SQL Agent StateGraph.
 * Defines node names, state keys, and prompt template names as constants.
 * <p>
 * Modeled after the reference project's DataAgentSpec pattern.
 */
public final class SqlAgentSpec {

    private SqlAgentSpec() {}

    public static final String GRAPH_NAME = "sql-agent-graph";

    // ======================== Node Names ========================

    public static final class Node {
        public static final String EVIDENCE_RECALL = "EVIDENCE_RECALL";
        public static final String SCHEMA_LINKING = "SCHEMA_LINKING";
        public static final String FEASIBILITY_ASSESSMENT = "FEASIBILITY_ASSESSMENT";
        public static final String PLANNER = "PLANNER";
        public static final String HITL_GATE = "HITL_GATE";
        public static final String HITL = "HITL";
        public static final String PLAN_DISPATCH = "PLAN_DISPATCH";
        public static final String SQL_GENERATION = "SQL_GENERATION";
        public static final String SQL_EXECUTION = "SQL_EXECUTION";
        public static final String SQL_FIXER = "SQL_FIXER";
        public static final String PYTHON_GENERATION = "PYTHON_GENERATION";
        public static final String PYTHON_EXECUTION = "PYTHON_EXECUTION";
        public static final String PYTHON_ANALYSIS = "PYTHON_ANALYSIS";
        public static final String REPORT = "REPORT";
        // ---- Task split workflow ----
        public static final String MEMORY_RECALL = "MEMORY_RECALL";
        public static final String ANALYZER = "ANALYZER";
        public static final String TASK_SPLIT = "TASK_SPLIT";
        public static final String TASK_DISPATCH = "TASK_DISPATCH";
        public static final String SUMMARIZE = "SUMMARIZE";
    }

    // ======================== State Keys ========================

    public static final class StateKey {
        // ---- Input ----
        public static final String INPUT = "input";
        public static final String USER_ID = "userId";
        public static final String CONNECTION_ID = "connectionId";
        public static final String LLM_CONFIG_ID = "llmConfigId";
        public static final String WORKSPACE_ID = "workspaceId";
        public static final String THREAD_ID = "threadId";
        public static final String SESSION_ID = "sessionId";
        public static final String CONVERSATION_ID = "conversationId";
        public static final String CONVERSATION_HISTORY = "conversationHistory";
        public static final String DB_TYPE = "dbType";
        public static final String SCHEMA_NAME = "schemaName";

        // ---- Evidence Recall ----
        public static final String REWRITE_QUERY = "rewriteQuery";
        public static final String EVIDENCE = "evidence";
        /** Structured glossary entries (List<Map>) recalled by Phase 5 RAG — for the frontend card. */
        public static final String EVIDENCE_GLOSSARY = "evidenceGlossary";
        /** Structured few-shot Q/A entries (List<Map>) recalled by Phase 5 RAG — for the frontend card. */
        public static final String EVIDENCE_FAQ = "evidenceFaq";

        // ---- Schema Linking ----
        public static final String TABLE_RELATION = "tableRelation";
        public static final String TABLE_NAMES = "tableNames";

        // ---- Feasibility ----
        public static final String FEASIBILITY_RESULT = "feasibilityResult";
        public static final String NEXT_NODE = "nextNode";

        // ---- Planning ----
        public static final String PLAN = "plan";
        public static final String CURRENT_STEP = "currentStep";
        public static final String REPAIR_COUNT = "repairCount";
        /** Accumulated per-step analysis conclusions (Map<Integer,String>) — consumed by the report node. */
        public static final String EXECUTION_OUTPUT = "executionOutput";

        // ---- HITL ----
        /** Frontend "auto-confirm" switch (true = skip the LLM gate entirely). */
        public static final String AUTO_CONFIRM = "autoConfirm";
        /** LLM-gate decision: does this plan need human review? */
        public static final String NEEDS_HUMAN_REVIEW = "needsHumanReview";
        public static final String CONFIRMATION_APPROVED = "confirmationApproved";
        public static final String CONFIRMATION_FEEDBACK = "confirmationFeedback";

        // ---- SQL Execution ----
        public static final String SQL_GENERATION_RESULT = "sqlGenerationResult";
        public static final String SQL_EXECUTION_RESULT = "sqlExecutionResult";
        public static final String SQL_ERROR = "sqlError";
        public static final String FIX_ATTEMPT_COUNT = "fixAttemptCount";

        // ---- Python ----
        public static final String PYTHON_CODE = "pythonCode";
        public static final String PYTHON_RESULT = "pythonResult";
        public static final String PYTHON_ANALYSIS_RESULT = "pythonAnalysisResult";

        // ---- Report ----
        public static final String REPORT_RESULT = "reportResult";

        // ---- Task Split workflow ----
        public static final String COMPLEXITY = "complexity";
        public static final String SUBTASKS = "subtasks";
        public static final String CURRENT_SUBTASK = "currentSubtask";
        public static final String SUBTASK_RESULTS = "subtaskResults";

        // ---- Trace context (carried through state) ----
        public static final String TRACE_CONTEXT = "traceContext";

        // ---- Memory ----
        public static final String USER_MEMORY = "userMemory";

        // ---- Agent Studio config ----
        public static final String AGENT_SYSTEM_PROMPT = "agentSystemPrompt";
        public static final String AGENT_MEMORY_ENABLED = "agentMemoryEnabled";
        public static final String AGENT_TOOLS = "agentTools";
        public static final String AGENT_NAME = "agentName";
        public static final String AGENT_ID = "agentId";
    }

    // ======================== Prompt Template Names ========================

    public static final class PromptName {
        public static final String EVIDENCE_QUERY_REWRITE = "evidence-query-rewrite";
        public static final String NEW_SQL_GENERATE = "new-sql-generate";
        public static final String REPORT_GENERATOR = "report-generator-plain";
        public static final String FEASIBILITY_ASSESSMENT = "feasibility-assessment";
        public static final String MIX_SELECTOR = "mix-selector";
        public static final String PLANNER = "planner";
        public static final String SQL_ERROR_FIXER = "sql-error-fixer";
        public static final String PYTHON_GENERATOR = "python-generator";
        public static final String PYTHON_ANALYZE = "python-analyze";
        public static final String HITL_GATE = "hitl-gate";
        /** Glossary knowledge wrapper (Phase 5 RAG) — {businessKnowledge}. */
        public static final String EVIDENCE_GLOSSARY = "evidence-glossary";
        /** Few-shot knowledge wrapper (Phase 5 RAG) — {agentKnowledge}. */
        public static final String EVIDENCE_KNOWLEDGE = "evidence-knowledge";
        public static final String COMPLEXITY_ANALYZER = "complexity-analyzer";
        public static final String TASK_SPLIT = "task-split";
        public static final String SUMMARIZE = "summarize";
        public static final String MEMORY_EXTRACTION = "memory-extraction";
    }

    // ======================== Retrieval ========================

    /**
     * pgvector metadata keys and channel types for the four-channel vector retrieval.
     * Modeled after the reference project's {@code DataAgentSpec.Retrieval}.
     * <p>
     * Documents in {@code vector_store} carry these as {@code metadata} for
     * {@code FilterExpressionBuilder} filtering. Partitioning is by
     * {@code userId} + {@code connectionId} (this project's multi-tenant identity)
     * and {@code vectorType}.
     */
    public static final class Retrieval {
        private Retrieval() {}

        public static final class DocumentMetadataKey {
            public static final String VECTOR_TYPE = "vectorType";
            public static final String USER_ID = "userId";
            public static final String CONNECTION_ID = "connectionId";
            public static final String KNOWLEDGE_ID = "knowledgeId";
        }

        public static final class VectorType {
            /** Business glossary term (user-managed). */
            public static final String GLOSSARY_KNOWLEDGE = "GLOSSARY_KNOWLEDGE";
            /** Few-shot Q/A pair (user-managed). */
            public static final String QUESTION_KNOWLEDGE = "QUESTION_KNOWLEDGE";
            /** Live-schema table doc — reserved for a future schema-vector channel. */
            public static final String TABLE = "TABLE";
            /** Live-schema column doc — reserved for a future schema-vector channel. */
            public static final String COLUMN = "COLUMN";
        }
    }
}