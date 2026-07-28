package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.ConnectorAppService;
import com.sql.logic.engine.common.dto.ActiveConnectorCreateRequest;
import com.sql.logic.engine.common.dto.ActiveConnectorResponse;
import com.sql.logic.engine.common.dto.ActiveConnectorUpdateRequest;
import com.sql.logic.engine.common.dto.ConnectorTemplateCreateRequest;
import com.sql.logic.engine.common.dto.ConnectorTemplateResponse;
import com.sql.logic.engine.common.dto.ConnectorTemplateUpdateRequest;
import com.sql.logic.engine.common.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD for connector templates and active connectors.
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {

    private final ConnectorAppService connectorAppService;

    public ConnectorController(ConnectorAppService connectorAppService) {
        this.connectorAppService = connectorAppService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    // ── Templates ────────────────────────────────────────────────────

    @GetMapping("/templates")
    public Result<List<ConnectorTemplateResponse>> listTemplates() {
        return Result.success(connectorAppService.listTemplates(getCurrentUserId()));
    }

    @PostMapping("/templates")
    public Result<ConnectorTemplateResponse> createTemplate(@RequestBody ConnectorTemplateCreateRequest request) {
        return Result.success(connectorAppService.createTemplate(getCurrentUserId(), request));
    }

    @PutMapping("/templates")
    public Result<ConnectorTemplateResponse> updateTemplate(@RequestBody ConnectorTemplateUpdateRequest request) {
        return Result.success(connectorAppService.updateTemplate(getCurrentUserId(), request));
    }

    @DeleteMapping("/templates/{templateId}")
    public Result<Void> deleteTemplate(@PathVariable Long templateId) {
        connectorAppService.deleteTemplate(getCurrentUserId(), templateId);
        return Result.success(null);
    }

    // ── Active Connectors ───────────────────────────────────────────

    @GetMapping("/active")
    public Result<List<ActiveConnectorResponse>> listActive() {
        return Result.success(connectorAppService.listActive(getCurrentUserId()));
    }

    @PostMapping("/active")
    public Result<ActiveConnectorResponse> createActive(@RequestBody ActiveConnectorCreateRequest request) {
        return Result.success(connectorAppService.createActive(getCurrentUserId(), request));
    }

    @PutMapping("/active")
    public Result<ActiveConnectorResponse> updateActive(@RequestBody ActiveConnectorUpdateRequest request) {
        return Result.success(connectorAppService.updateActive(getCurrentUserId(), request));
    }

    @DeleteMapping("/active/{activeId}")
    public Result<Void> deleteActive(@PathVariable Long activeId) {
        connectorAppService.deleteActive(getCurrentUserId(), activeId);
        return Result.success(null);
    }
}
