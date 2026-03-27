package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.DatabaseAppService;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/database")
public class DatabaseController {

    private final DatabaseAppService databaseAppService;

    public DatabaseController(DatabaseAppService databaseAppService) {
        this.databaseAppService = databaseAppService;
    }

    @GetMapping("/list")
    public Result<List<DbConnectionConf>> listConnections(@RequestParam Long userId) {
        try {
            List<DbConnectionConf> list = databaseAppService.getUserConnections(userId);
            return Result.success(list);
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/add")
    public Result<DbConnectionConf> addConnection(@RequestBody DbConnectionConf conf) {
        try {
            DbConnectionConf saved = databaseAppService.addConnection(conf);
            return Result.success(saved);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PutMapping("/update")
    public Result<DbConnectionConf> updateConnection(@RequestParam Long userId, @RequestBody DbConnectionConf conf) {
        try {
            DbConnectionConf updated = databaseAppService.updateConnection(userId, conf);
            return Result.success(updated);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> deleteConnection(@RequestParam Long userId, @PathVariable("id") Long connectionId) {
        try {
            databaseAppService.deleteConnection(userId, connectionId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @PostMapping("/test")
    public Result<Boolean> testConnection(@RequestBody DbConnectionConf conf) {
        try {
            boolean success = databaseAppService.testConnection(conf);
            return Result.success(success);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "Connection error: " + e.getMessage());
        }
    }
}