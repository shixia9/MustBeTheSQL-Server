package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.SQLGenerateAppService;
import com.sql.logic.engine.trigger.http.dto.SqlGenerateRequest;
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
        return sqlGenerateAppService.generateSqlStream(
                request.getUserInput(), 
                request.getSchemaContext(), 
                request.getStrategyName()
        );
    }
}
