package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.SQLExecuteAppService;
import com.sql.logic.engine.trigger.http.dto.SqlExecuteRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sql")
public class SQLExecuteController {

    private final SQLExecuteAppService sqlExecuteAppService;

    public SQLExecuteController(SQLExecuteAppService sqlExecuteAppService) {
        this.sqlExecuteAppService = sqlExecuteAppService;
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executeSql(@RequestBody SqlExecuteRequest request) {
        try {
            List<Map<String, Object>> result = sqlExecuteAppService.executeQuery(request.getSql());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
