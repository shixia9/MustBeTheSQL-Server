package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.common.response.Result;
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

    public MemoryController(MemoryItemDao memoryItemDao, MemoryExtractorService memoryExtractorService) {
        this.memoryItemDao = memoryItemDao;
        this.memoryExtractorService = memoryExtractorService;
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
        MemoryItem item = new MemoryItem();
        item.setUserId(userId);
        item.setType(body.get("type") != null ? String.valueOf(body.get("type")).toUpperCase() : null);
        item.setContent(body.get("content") != null ? String.valueOf(body.get("content")) : null);
        if (body.get("importance") != null) {
            try {
                item.setImportance(new BigDecimal(String.valueOf(body.get("importance"))));
            } catch (NumberFormatException e) {
                item.setImportance(BigDecimal.valueOf(0.5));
            }
        }
        @SuppressWarnings("unchecked")
        List<String> tags = body.get("tags") instanceof List
                ? (List<String>) body.get("tags") : List.of();
        item.setTags(tags);
        item.setStatus(1);
        item.setCreateTime(LocalDateTime.now());
        item.setUpdateTime(LocalDateTime.now());
        memoryItemDao.insert(item);
        return Result.success(item);
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
