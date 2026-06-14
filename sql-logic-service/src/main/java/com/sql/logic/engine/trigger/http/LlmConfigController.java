package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.LlmConfigAppService;
import com.sql.logic.engine.common.dto.LlmConfigCreateRequest;
import com.sql.logic.engine.common.dto.LlmConfigResponse;
import com.sql.logic.engine.common.dto.LlmConfigUpdateRequest;
import com.sql.logic.engine.common.response.Result;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/llm-config")
public class LlmConfigController {

    private final LlmConfigAppService llmConfigAppService;

    public LlmConfigController(LlmConfigAppService llmConfigAppService) {
        this.llmConfigAppService = llmConfigAppService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @GetMapping("/list")
    public Result<List<LlmConfigResponse>> listConfigs() {
        return Result.success(llmConfigAppService.listConfigs(getCurrentUserId()));
    }

    @PostMapping("/create")
    public Result<LlmConfigResponse> createConfig(@RequestBody LlmConfigCreateRequest request) {
        return Result.success(llmConfigAppService.createConfig(getCurrentUserId(), request));
    }

    @PutMapping("/update")
    public Result<LlmConfigResponse> updateConfig(@RequestBody LlmConfigUpdateRequest request) {
        return Result.success(llmConfigAppService.updateConfig(getCurrentUserId(), request));
    }

    @DeleteMapping("/{configId}")
    public Result<Void> deleteConfig(@PathVariable Long configId) {
        llmConfigAppService.deleteConfig(getCurrentUserId(), configId);
        return Result.success(null);
    }

    @PostMapping("/{configId}/setDefault")
    public Result<Void> setDefaultConfig(@PathVariable Long configId) {
        llmConfigAppService.setDefaultConfig(getCurrentUserId(), configId);
        return Result.success(null);
    }
}