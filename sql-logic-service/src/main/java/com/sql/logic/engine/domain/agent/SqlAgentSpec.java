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
        public static final String HITL = "HITL";
        public static final String PLAN_DISPATCH = "PLAN_DISPATCH";
        public static final String SQL_GENERATION = "SQL_GENERATION";
        public static final String SQL_EXECUTION = "SQL_EXECUTION";
        public static final String SQL_FIXER = "SQL_FIXER";
        public static final String PYTHON_GENERATION = "PYTHON_GENERATION";
        public static final String PYTHON_EXECUTION = "PYTHON_EXECUTION";
        public static final String PYTHON_ANALYSIS = "PYTHON_ANALYSIS";
        public static final String REPORT = "REPORT";
    }

    // ======================== State Keys ========================

    public static final class StateKey {
        // ---- Input ----
        public static final String INPUT = "input";
        public static final String USER_ID = "userId";
        public static final String CONNECTION_ID = "connectionId";
        public static final String LLM_CONFIG_ID = "llmConfigId";
        public static final String DB_TYPE = "dbType";

        // ---- Evidence Recall ----
        public static final String REWRITE_QUERY = "rewriteQuery";
        public static final String EVIDENCE = "evidence";

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

        // ---- HITL ----
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
    }
}