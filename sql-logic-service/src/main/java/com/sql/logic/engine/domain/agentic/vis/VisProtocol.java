package com.sql.logic.engine.domain.agentic.vis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Vis protocol base — defines how visualization data is serialized
 * for transmission to the frontend.
 * <p>
 * Output is wrapped in markdown code fences: {@code ```vis-<tag>\n<JSON>\n```}
 * which the frontend parses to extract structured visualization data.
 */
public sealed class VisProtocol permits VisChart, VisDashboard {

    private static final Logger log = LoggerFactory.getLogger(VisProtocol.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String tag;

    protected VisProtocol(String tag) {
        this.tag = tag;
    }

    /**
     * Wrap params as a markdown code-fence block for frontend parsing.
     */
    public String display(Map<String, Object> params) {
        try {
            String json = mapper.writeValueAsString(params);
            return "\n```" + tag + "\n" + json + "\n```\n";
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize vis params", e);
            return "";
        }
    }

    public String visTag() {
        return tag;
    }
}
