package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.SQLGenerateAppService;
import com.sql.logic.engine.common.context.SecurityContext;
import com.sql.logic.engine.common.dto.SqlGenerateRequest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/sql")
public class SQLGenerateController {

    private final SQLGenerateAppService sqlGenerateAppService;

    public SQLGenerateController(SQLGenerateAppService sqlGenerateAppService) {
        this.sqlGenerateAppService = sqlGenerateAppService;
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateSqlStream(@RequestBody SqlGenerateRequest request) {
        Long currentUserId = SecurityContext.getCurrentUserId();
        if (request.getUserId() == null) {
            return Flux.error(new IllegalArgumentException("UserId is required"));
        }
        // Verify userId matches logged-in user
        if (!request.getUserId().equals(currentUserId)) {
            return Flux.error(new IllegalArgumentException("UserId does not match logged-in user"));
        }
        return sqlGenerateAppService.generateSqlStream(
                request.getUserId(),
                request.getUserInput(),
                request.getConnectionId(),
                request.getTableNames(),
                request.getSchemaContext(),
                request.getStrategyName(),
                request.getParentHistoryId()
        );
    }
}