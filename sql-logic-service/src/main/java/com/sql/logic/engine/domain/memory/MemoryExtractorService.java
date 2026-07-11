package com.sql.logic.engine.domain.memory;

import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MemoryExtractorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractorService.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final MemoryDomainService memoryDomainService;

    public MemoryExtractorService(LlmClientManager llmClientManager,
                                  PromptManager promptManager,
                                  MemoryDomainService memoryDomainService) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.memoryDomainService = memoryDomainService;
    }

    @Async
    public void extractAndPersistAsync(Long userId, Long workspaceId, String threadId,
                                        String userInput, String sessionSummary, Long llmConfigId) {
        try {
            log.info("[MemoryExtractorService] Starting extraction for userId={}, threadId={}, llmConfigId={}",
                    userId, threadId, llmConfigId);

            String prompt = promptManager.render(SqlAgentSpec.PromptName.MEMORY_EXTRACTION, Map.of(
                    "user_input", userInput == null ? "" : userInput,
                    "session_summary", sessionSummary == null ? "" : sessionSummary
            ));

            LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId != null ? llmConfigId : 0L,
                    userId, null, "MEMORY_EXTRACTION", null);
            String response = strategy.generateSql(prompt, null);

            if (response == null || response.isBlank()) {
                log.debug("[MemoryExtractorService] Empty LLM response for threadId={}", threadId);
                return;
            }

            log.debug("[MemoryExtractorService] LLM response received, parsing XML (len={})", response.length());
            List<CandidateMemory> candidates = parseXmlMemories(response);
            if (candidates.isEmpty()) {
                log.debug("[MemoryExtractorService] No memories extracted from response for threadId={}", threadId);
                return;
            }

            int saved = memoryDomainService.saveMemories(userId, workspaceId, threadId, candidates);
            log.info("[MemoryExtractorService] Extracted and saved {} memories for userId={}, threadId={}",
                    saved, userId, threadId);
        } catch (Exception e) {
            log.warn("[MemoryExtractorService] Extraction failed for userId={}, threadId={}: {}",
                    userId, threadId, e.getMessage(), e);
        }
    }

    private List<CandidateMemory> parseXmlMemories(String raw) {
        List<CandidateMemory> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;

        // LLM responses often wrap XML in markdown or add preamble text.
        // Extract just the <memories>...</memories> block.
        String xml = raw;
        int start = raw.indexOf("<memories>");
        int end = raw.lastIndexOf("</memories>");
        if (start >= 0 && end > start) {
            xml = raw.substring(start, end + "</memories>".length());
        }
        // Strip ```xml / ``` fences if present.
        xml = xml.replaceAll("(?i)```(?:xml)?\\s*", "").trim();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(xml)));

            NodeList memoryNodes = doc.getElementsByTagName("memory");
            for (int i = 0; i < memoryNodes.getLength(); i++) {
                Element memElem = (Element) memoryNodes.item(i);
                CandidateMemory cm = new CandidateMemory();
                cm.setType(childText(memElem, "type"));
                cm.setText(childText(memElem, "text"));
                cm.setImportance(parseDouble(childText(memElem, "importance"), 0.5));
                cm.setTags(parseTags(childText(memElem, "tags")));
                cm.setData(childText(memElem, "data"));
                if (cm.getText() != null && !cm.getText().isBlank()) {
                    result.add(cm);
                }
            }
        } catch (Exception e) {
            log.warn("[MemoryExtractorService] XML parsing failed: {}", e.getMessage());
            return List.of();
        }
        return result;
    }

    private String childText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() == 0) {
            return null;
        }
        return list.item(0).getTextContent().trim();
    }

    private double parseDouble(String value, double defaultVal) {
        if (value == null || value.isBlank()) {
            return defaultVal;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private List<String> parseTags(String tagsStr) {
        if (tagsStr == null || tagsStr.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String part : tagsStr.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }
}
