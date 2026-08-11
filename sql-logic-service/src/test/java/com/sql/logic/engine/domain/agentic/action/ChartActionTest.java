package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.model.SqlExecutionResult;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import com.sql.logic.engine.domain.agentic.core.ActionOutput;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import com.sql.logic.engine.domain.agentic.vis.VisChart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ChartActionTest {

    private ChartAction chartAction;
    private StubSqlExecutionService stubService;

    @BeforeEach
    void setUp() {
        stubService = new StubSqlExecutionService();
        chartAction = new ChartAction(stubService, new VisChart());
    }

    @Test
    void shouldHaveCorrectName() {
        assertEquals("chart", chartAction.name());
    }

    @Test
    void shouldHaveDescription() {
        assertNotNull(chartAction.description());
    }

    @Test
    void shouldGenerateChartFromJsonInput() throws Exception {
        String llmOutput = """
                {
                    "display_type": "response_bar_chart",
                    "sql": "SELECT city, COUNT(*) as cnt FROM users GROUP BY city",
                    "thought": "City distribution analysis"
                }""";

        AgentMessage msg = AgentMessage.builder()
                .content(llmOutput)
                .build();

        CompletableFuture<ActionOutput> future = chartAction.execute(msg, null);
        ActionOutput result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isExeSuccess());
        assertTrue(result.content().contains("vis-db-chart"));
        assertEquals("response_bar_chart", result.data().get("displayType"));
    }

    @Test
    void shouldFallbackToTableForPlainSql() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .content("SELECT * FROM users")
                .build();

        CompletableFuture<ActionOutput> future = chartAction.execute(msg, null);
        ActionOutput result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isExeSuccess());
        assertEquals("response_table", result.data().get("displayType"));
    }

    @Test
    void shouldFailForNonParseableContent() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .content("This is not SQL")
                .build();

        CompletableFuture<ActionOutput> future = chartAction.execute(msg, null);
        ActionOutput result = future.get(5, TimeUnit.SECONDS);

        assertFalse(result.isExeSuccess());
        assertTrue(result.content().contains("No SQL found"));
    }

    @Test
    void shouldHandleExecutionError() throws Exception {
        stubService.throwError = true;

        AgentMessage msg = AgentMessage.builder()
                .content("SELECT * FROM nonexistent_table")
                .build();

        CompletableFuture<ActionOutput> future = chartAction.execute(msg, null);
        ActionOutput result = future.get(5, TimeUnit.SECONDS);

        assertFalse(result.isExeSuccess());
        assertTrue(result.content().contains("failed"));
    }

    @Test
    void shouldHandleEmptyContent() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .content("")
                .build();

        CompletableFuture<ActionOutput> future = chartAction.execute(msg, null);
        ActionOutput result = future.get(5, TimeUnit.SECONDS);

        assertFalse(result.isExeSuccess());
    }

    @Test
    void shouldExtractSqlFromPreviousActionReport() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .content("") // no direct content
                .actionReport(new ActionOutput(true, "SELECT city, COUNT(*) FROM users GROUP BY city",
                        Map.of(), List.of(), false))
                .build();

        CompletableFuture<ActionOutput> future = chartAction.execute(msg, null);
        ActionOutput result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isExeSuccess());
        assertTrue(result.content().contains("vis-db-chart"));
    }

    // Stub implementation
    private static class StubSqlExecutionService extends SqlExecutionService {
        boolean throwError = false;

        StubSqlExecutionService() {
            super(null);
        }

        @Override
        public SqlExecutionResult execute(Long userId, Long connectionId, String sql) {
            if (throwError) throw new RuntimeException("Simulated error");
            return new SqlExecutionResult(
                    List.of("city", "cnt"),
                    List.of(
                            Map.of("city", "Beijing", "cnt", 100),
                            Map.of("city", "Shanghai", "cnt", 200)
                    ),
                    2
            );
        }
    }
}
