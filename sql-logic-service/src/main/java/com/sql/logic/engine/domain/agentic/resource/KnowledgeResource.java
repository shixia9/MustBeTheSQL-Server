package com.sql.logic.engine.domain.agentic.resource;

import com.sql.logic.engine.application.service.VectorSearchService;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Knowledge base resource — retrieves FAQ and glossary entries
 * via the existing {@link VectorSearchService} and injects them as
 * prompt text in the Agent's system prompt.
 * <p>
 * This replaces the independent EVIDENCE_RECALL StateGraph node.
 * The knowledge is loaded as plain text during {@code loadThinkingMessages()},
 * requiring zero extra LLM calls.
 */
public class KnowledgeResource implements AgentResource {

    private final VectorSearchService vectorSearchService;
    private final Long userId;
    private final Long connectionId;

    public KnowledgeResource(VectorSearchService vectorSearchService, Long userId, Long connectionId) {
        this.vectorSearchService = vectorSearchService;
        this.userId = userId;
        this.connectionId = connectionId;
    }

    @Override
    public String name() {
        return "knowledge";
    }

    @Override
    public String getPrompt(String observation) {
        if (observation == null || observation.isBlank()) return "";
        if (vectorSearchService == null) return "";
        StringBuilder sb = new StringBuilder();

        try {
            // FAQ / question-answer pairs
            List<Document> questionDocs =
                    vectorSearchService.recallQuestion(observation, userId, connectionId);
            if (questionDocs != null && !questionDocs.isEmpty()) {
                sb.append("### 相关知识（FAQ）\n");
                for (Document doc : questionDocs) {
                    if (doc.getText() != null && !doc.getText().isBlank()) {
                        sb.append("- ").append(doc.getText()).append("\n");
                    }
                }
                sb.append("\n");
            }
        } catch (Exception ignored) {}

        try {
            // Glossary / business terms
            List<Document> glossaryDocs =
                    vectorSearchService.recallGlossary(observation, userId, connectionId);
            if (glossaryDocs != null && !glossaryDocs.isEmpty()) {
                sb.append("### 业务术语表\n");
                for (Document doc : glossaryDocs) {
                    if (doc.getText() != null && !doc.getText().isBlank()) {
                        sb.append("- ").append(doc.getText()).append("\n");
                    }
                }
                sb.append("\n");
            }
        } catch (Exception ignored) {}

        return sb.toString();
    }

    @Override
    public String getPrompt(AgentMessage message) {
        return getPrompt(message != null ? message.content() : "");
    }
}
