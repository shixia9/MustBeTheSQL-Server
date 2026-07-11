package com.sql.logic.engine.trigger.http;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.application.service.ConversationAppService;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.infrastructure.po.Conversation;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;

import cn.dev33.satoken.stp.StpUtil;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationAppService conversationAppService;

    public ConversationController(ConversationAppService conversationAppService) {
        this.conversationAppService = conversationAppService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @PostMapping
    public Result<Conversation> createConversation(@RequestBody Map<String, Object> req) {
        Long userId = getCurrentUserId();
        String title = (String) req.getOrDefault("title", "New Conversation");
        Long strategyId = Long.valueOf(req.getOrDefault("llmStrategyId", 1).toString());

        return Result.success(conversationAppService.createConversation(userId, title, strategyId));
    }

    @GetMapping("/user/{userId}")
    public Result<Page<Conversation>> listConversations(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Long loginUserId = getCurrentUserId();
        if (!loginUserId.equals(userId)) {
            return Result.error(403, "Access denied");
        }
        return Result.success(conversationAppService.listConversations(userId, page, size, keyword, startDate, endDate));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id) {
        Long loginUserId = getCurrentUserId();
        // Verify ownership — only the conversation owner can delete
        Conversation conv = conversationAppService.listConversations(loginUserId).stream()
                .filter(c -> c.getId().equals(id)).findFirst().orElse(null);
        if (conv == null || !loginUserId.equals(conv.getUserId())) {
            return Result.error(403, "Access denied or conversation not found");
        }
        conversationAppService.deleteConversation(id);
        return Result.success(null);
    }

    @PostMapping("/{conversationId}/details")
    public Result<String> addDetail(@PathVariable Long conversationId, @RequestBody Map<String, String> req) {
        conversationAppService.addDetail(
                conversationId,
                req.get("userInput"),
                req.get("sqlOutput"),
                req.get("executeResult")
        );
        return Result.success("success");
    }

    @GetMapping("/{conversationId}/details")
    public Result<List<ConversationDetail>> getDetails(@PathVariable Long conversationId) {
        return Result.success(conversationAppService.getConversationDetails(conversationId));
    }
}