package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.ConversationAppService;
import com.sql.logic.engine.infrastructure.po.Conversation;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
@SaCheckLogin
public class ConversationController {

    private final ConversationAppService conversationAppService;

    public ConversationController(ConversationAppService conversationAppService) {
        this.conversationAppService = conversationAppService;
    }

    @PostMapping
    public Result<Conversation> createConversation(@RequestBody Map<String, Object> req) {
        Long userId = StpUtil.getLoginIdAsLong();
        String title = (String) req.getOrDefault("title", "New Conversation");
        Long strategyId = Long.valueOf(req.getOrDefault("llmStrategyId", 1).toString());

        return Result.success(conversationAppService.createConversation(userId, title, strategyId));
    }

    @GetMapping("/user/{userId}")
    public Result<List<Conversation>> listConversations(@PathVariable Long userId) {
        // Verify the requesting user matches the path userId
        Long loginUserId = StpUtil.getLoginIdAsLong();
        if (!loginUserId.equals(userId)) {
            return Result.error(403, "Access denied");
        }
        return Result.success(conversationAppService.listConversations(userId));
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