package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.memory.CandidateMemory;
import com.sql.logic.engine.domain.memory.MemoryDomainService;
import com.sql.logic.engine.domain.memory.MemoryExtractorService;
import com.sql.logic.engine.infrastructure.dao.MemoryItemDao;
import com.sql.logic.engine.infrastructure.po.MemoryItem;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final MemoryItemDao memoryItemDao;
    private final MemoryExtractorService memoryExtractorService;
    private final MemoryDomainService memoryDomainService;

    public MemoryController(MemoryItemDao memoryItemDao,
                            MemoryExtractorService memoryExtractorService,
                            MemoryDomainService memoryDomainService) {
        this.memoryItemDao = memoryItemDao;
        this.memoryExtractorService = memoryExtractorService;
        this.memoryDomainService = memoryDomainService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @GetMapping("/list")
    public Result<List<MemoryItem>> list(@RequestParam(required = false) String type) {
        Long userId = getCurrentUserId();
        QueryWrapper<MemoryItem> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        query.eq("status", 1);
        if (type != null && !type.isBlank()) {
            query.eq("type", type.toUpperCase());
        }
        query.orderByDesc("importance");
        return Result.success(memoryItemDao.selectList(query));
    }

    @PostMapping
    public Result<MemoryItem> create(@RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        String type = body.get("type") != null ? String.valueOf(body.get("type")).toUpperCase() : "PROFILE";
        String content = body.get("content") != null ? String.valueOf(body.get("content")) : null;
        if (content == null || content.isBlank()) {
            return Result.error(400, "Content cannot be empty");
        }
        double importance = 0.5;
        if (body.get("importance") != null) {
            try {
                importance = new BigDecimal(String.valueOf(body.get("importance"))).doubleValue();
            } catch (NumberFormatException ignored) {}
        }
        @SuppressWarnings("unchecked")
        List<String> tags = body.get("tags") instanceof List
                ? (List<String>) body.get("tags") : List.of();

        CandidateMemory candidate = new CandidateMemory();
        candidate.setType(type);
        candidate.setText(content);
        candidate.setImportance(importance);
        candidate.setTags(tags);

        int saved = memoryDomainService.saveMemories(userId, null, null, List.of(candidate));
        if (saved > 0) {
            return Result.success(null);
        }
        return Result.error(500, "Failed to save memory");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        MemoryItem item = memoryItemDao.selectById(id);
        if (item == null || !item.getUserId().equals(userId)) {
            return Result.error(404, "Memory not found");
        }
        item.setStatus(0);
        item.setUpdateTime(LocalDateTime.now());
        memoryItemDao.updateById(item);
        return Result.success(null);
    }

    /**
     * Phase B memory extraction (manual trigger #3): let the user explicitly ask the
     * Agent to memorise a session. Accepts the original user input plus a session
     * transcript (report + SQL). Runs async — returns immediately.
     */
    @PostMapping("/extract")
    public Result<Void> extract(@RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        String userInput = body.get("userInput") != null ? String.valueOf(body.get("userInput")) : "";
        String summary = body.get("summary") != null ? String.valueOf(body.get("summary")) : "";
        String threadId = body.get("threadId") != null ? String.valueOf(body.get("threadId")) : null;
        memoryExtractorService.extractAndPersistAsync(userId, null, threadId, userInput, summary);
        return Result.success(null);
    }
}
