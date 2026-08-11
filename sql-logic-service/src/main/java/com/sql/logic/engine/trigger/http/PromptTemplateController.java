package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.PromptTemplateAppService;
import com.sql.logic.engine.common.dto.PromptTemplateCreateRequest;
import com.sql.logic.engine.common.dto.PromptTemplateResponse;
import com.sql.logic.engine.common.dto.PromptTemplateUpdateRequest;
import com.sql.logic.engine.common.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD for user-managed reusable prompt templates.
 */
@RestController
@RequestMapping("/api/v1/prompts")
public class PromptTemplateController {

    private final PromptTemplateAppService promptTemplateAppService;

    public PromptTemplateController(PromptTemplateAppService promptTemplateAppService) {
        this.promptTemplateAppService = promptTemplateAppService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @GetMapping("/list")
    public Result<List<PromptTemplateResponse>> list() {
        return Result.success(promptTemplateAppService.list(getCurrentUserId()));
    }

    @PostMapping("/create")
    public Result<PromptTemplateResponse> create(@RequestBody PromptTemplateCreateRequest request) {
        return Result.success(promptTemplateAppService.create(getCurrentUserId(), request));
    }

    @PutMapping("/update")
    public Result<PromptTemplateResponse> update(@RequestBody PromptTemplateUpdateRequest request) {
        return Result.success(promptTemplateAppService.update(getCurrentUserId(), request));
    }

    @DeleteMapping("/{promptId}")
    public Result<Void> delete(@PathVariable Long promptId) {
        promptTemplateAppService.delete(getCurrentUserId(), promptId);
        return Result.success(null);
    }
}
