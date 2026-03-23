package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.ConversationAppService;
import com.sql.logic.engine.infrastructure.po.Conversation;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import org.springframework.http.ResponseEntity;
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

    @PostMapping
    public ResponseEntity<Conversation> createConversation(@RequestBody Map<String, Object> req) {
        Long userId = Long.valueOf(req.getOrDefault("userId", 1).toString());
        String title = (String) req.getOrDefault("title", "New Conversation");
        Long strategyId = Long.valueOf(req.getOrDefault("llmStrategyId", 1).toString());
        
        return ResponseEntity.ok(conversationAppService.createConversation(userId, title, strategyId));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Conversation>> listConversations(@PathVariable Long userId) {
        return ResponseEntity.ok(conversationAppService.listConversations(userId));
    }

    @PostMapping("/{conversationId}/details")
    public ResponseEntity<String> addDetail(@PathVariable Long conversationId, @RequestBody Map<String, String> req) {
        conversationAppService.addDetail(
                conversationId,
                req.get("userInput"),
                req.get("sqlOutput"),
                req.get("executeResult")
        );
        return ResponseEntity.ok("success");
    }

    @GetMapping("/{conversationId}/details")
    public ResponseEntity<List<ConversationDetail>> getDetails(@PathVariable Long conversationId) {
        return ResponseEntity.ok(conversationAppService.getConversationDetails(conversationId));
    }
}
