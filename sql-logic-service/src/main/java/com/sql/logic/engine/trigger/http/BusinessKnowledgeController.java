package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.BusinessKnowledgeAppService;
import com.sql.logic.engine.common.dto.BusinessKnowledgeCreateRequest;
import com.sql.logic.engine.common.dto.BusinessKnowledgeResponse;
import com.sql.logic.engine.common.dto.BusinessKnowledgeUpdateRequest;
import com.sql.logic.engine.common.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Phase 5 — user-managed business knowledge (glossary + few-shot QA) CRUD.
 * Each row is embedded into pgvector on save and removed on delete.
 */
@RestController
@RequestMapping("/api/v1/business-knowledge")
public class BusinessKnowledgeController {

    private final BusinessKnowledgeAppService businessKnowledgeAppService;

    public BusinessKnowledgeController(BusinessKnowledgeAppService businessKnowledgeAppService) {
        this.businessKnowledgeAppService = businessKnowledgeAppService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @GetMapping("/list")
    public Result<List<BusinessKnowledgeResponse>> list(@RequestParam Long connectionId) {
        return Result.success(businessKnowledgeAppService.list(getCurrentUserId(), connectionId));
    }

    @PostMapping("/create")
    public Result<BusinessKnowledgeResponse> create(@RequestBody BusinessKnowledgeCreateRequest request) {
        return Result.success(businessKnowledgeAppService.create(getCurrentUserId(), request));
    }

    @PutMapping("/update")
    public Result<BusinessKnowledgeResponse> update(@RequestBody BusinessKnowledgeUpdateRequest request) {
        return Result.success(businessKnowledgeAppService.update(getCurrentUserId(), request));
    }

    @DeleteMapping("/{knowledgeId}")
    public Result<Void> delete(@PathVariable Long knowledgeId) {
        businessKnowledgeAppService.delete(getCurrentUserId(), knowledgeId);
        return Result.success(null);
    }
}