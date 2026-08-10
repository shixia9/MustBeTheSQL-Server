package com.sql.logic.admin.service;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads/writes the {@code bus-orc} section of the {@code sql-logic-service.yml}
 * Nacos config. Encapsulates {@link ConfigService} so controllers stay thin.
 * <p>
 * Only the {@code bus-orc} segment is touched on write (regex locate-and-replace);
 * the rest of the YAML (including comments and sensitive keys like passwords /
 * api-keys) is preserved verbatim — this is deliberately NOT a generic YAML editor.
 * <p>
 * The dataId {@code sql-logic-service.yml} is read cross-module from the admin
 * app (admin itself imports {@code sql-logic-admin.yml}); {@link ConfigService}
 * can read any dataId in the same namespace/group.
 */
@Service
public class NacosConfigService {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigService.class);

    /** dataId of the multi-agent service config that owns {@code bus-orc}. */
    private static final String DATA_ID = "sql-logic-service.yml";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String TYPE = "yaml";
    private static final long READ_TIMEOUT_MS = 5000L;

    /** Matches the top-level {@code bus-orc:} block up to the next non-indented line or EOF. */
    private static final Pattern BUS_ORC_SECTION =
            Pattern.compile("(?ms)^bus-orc:\\s*\\n(.*?)(?=^\\S|\\Z)");
    private static final Pattern MODE_LINE =
            Pattern.compile("(?m)^\\s*mode:\\s*([A-Za-z]+)");
    private static final Pattern TIMEOUT_LINE =
            Pattern.compile("(?m)^\\s*dispatcher-timeout-seconds:\\s*(\\d+)");

    private final ConfigService configService;

    @Autowired
    public NacosConfigService(NacosConfigManager nacosConfigManager) {
        this.configService = nacosConfigManager.getConfigService();
    }

    /**
     * Returns current bus-orc config as an ordered map
     * {@code {mode, dispatcherTimeoutSeconds}}; falls back to defaults
     * {@code off/300} when the section is absent or Nacos is unreachable.
     */
    public Map<String, Object> readBusOrc() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "off");
        result.put("dispatcherTimeoutSeconds", 300L);
        try {
            String yaml = configService.getConfig(DATA_ID, GROUP, READ_TIMEOUT_MS);
            if (yaml == null || yaml.isBlank()) {
                return result;
            }
            Matcher m = BUS_ORC_SECTION.matcher(yaml);
            if (!m.find()) {
                return result;
            }
            String section = m.group(1);
            Matcher mm = MODE_LINE.matcher(section);
            if (mm.find()) {
                result.put("mode", mm.group(1).toLowerCase());
            }
            Matcher tm = TIMEOUT_LINE.matcher(section);
            if (tm.find()) {
                result.put("dispatcherTimeoutSeconds", Long.parseLong(tm.group(1)));
            }
        } catch (NacosException e) {
            log.warn("[NacosConfigService] read bus-orc failed: {}", e.getMessage());
            result.put("error", "Nacos unreadable: " + e.getMessage());
        }
        return result;
    }

    /**
     * Validates and writes bus-orc back to Nacos (segment replace, rest preserved).
     *
     * @throws IllegalArgumentException for invalid mode/timeout values
     * @throws IllegalStateException    if Nacos publish fails
     */
    public void writeBusOrc(String mode, long timeout) {
        if (!isValidMode(mode)) {
            throw new IllegalArgumentException(
                    "Invalid bus-orc mode: " + mode + " (expected off/bypass/switch)");
        }
        if (timeout < 10 || timeout > 3600) {
            throw new IllegalArgumentException(
                    "dispatcher-timeout-seconds must be in [10, 3600], got " + timeout);
        }
        try {
            String yaml = configService.getConfig(DATA_ID, GROUP, READ_TIMEOUT_MS);
            if (yaml == null) {
                yaml = "";
            }
            String newSection = "bus-orc:\n  mode: " + mode.toLowerCase() + "\n"
                    + "  dispatcher-timeout-seconds: " + timeout + "\n";
            Matcher m = BUS_ORC_SECTION.matcher(yaml);
            String updated;
            if (m.find()) {
                // Replace only the bus-orc block; quoteReplacement avoids $/\ interpretation.
                updated = m.replaceFirst(Matcher.quoteReplacement(newSection));
            } else {
                // Section absent — append at end (ensure a blank separator line).
                updated = (yaml.endsWith("\n") ? yaml : yaml + "\n") + "\n" + newSection;
            }
            boolean ok = configService.publishConfig(DATA_ID, GROUP, updated, TYPE);
            if (!ok) {
                throw new IllegalStateException("Nacos publishConfig returned false");
            }
            log.info("[NacosConfigService] bus-orc updated: mode={}, timeout={}s", mode, timeout);
        } catch (NacosException e) {
            log.error("[NacosConfigService] write bus-orc failed: {}", e.getMessage());
            throw new IllegalStateException("Failed to publish Nacos config: " + e.getMessage(), e);
        }
    }

    private boolean isValidMode(String mode) {
        return "off".equalsIgnoreCase(mode)
                || "bypass".equalsIgnoreCase(mode)
                || "switch".equalsIgnoreCase(mode);
    }
}
