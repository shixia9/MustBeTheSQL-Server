package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.WorkspaceAppService;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.infrastructure.dialect.model.ColumnDTO;
import com.sql.logic.engine.infrastructure.dialect.model.IndexDTO;
import com.sql.logic.engine.infrastructure.dialect.model.SchemaDTO;
import com.sql.logic.engine.infrastructure.dialect.model.TableDTO;

import cn.dev33.satoken.stp.StpUtil;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {

    private final WorkspaceAppService workspaceAppService;

    public WorkspaceController(WorkspaceAppService workspaceAppService) {
        this.workspaceAppService = workspaceAppService;
    }

    @GetMapping("/schemas")
    public Result<List<SchemaDTO>> getSchemas(@RequestParam Long connectionId) {
        String userIdStr = (String) StpUtil.getLoginId();
        if (userIdStr == null || !userIdStr.matches("\\d+")) {
            return Result.error(400, "Invalid user ID in session");
        }
        Long userId = Long.valueOf(userIdStr);
        return Result.success(workspaceAppService.getSchemas(userId, connectionId));
    }

    @GetMapping("/tables")
    public Result<List<TableDTO>> getTables(@RequestParam Long connectionId,
                                            @RequestParam(required = false) String schemaName) {
        String userIdStr = (String) StpUtil.getLoginId();
        if (userIdStr == null || !userIdStr.matches("\\d+")) {
            return Result.error(400, "Invalid user ID in session");
        }
        Long userId = Long.valueOf(userIdStr);
        return Result.success(workspaceAppService.getTables(userId, connectionId, schemaName));
    }

    @GetMapping("/columns")
    public Result<List<ColumnDTO>> getColumns(@RequestParam Long connectionId,
                                              @RequestParam(required = false) String schemaName,
                                              @RequestParam String tableName) {
        String userIdStr = (String) StpUtil.getLoginId();
        if (userIdStr == null || !userIdStr.matches("\\d+")) {
            return Result.error(400, "Invalid user ID in session");
        }
        Long userId = Long.valueOf(userIdStr);
        return Result.success(workspaceAppService.getColumns(userId, connectionId, schemaName, tableName));
    }

    @GetMapping("/indexes")
    public Result<List<IndexDTO>> getIndexes(@RequestParam Long connectionId,
                                             @RequestParam(required = false) String schemaName,
                                             @RequestParam String tableName) {
        String userIdStr = (String) StpUtil.getLoginId();
        if (userIdStr == null || !userIdStr.matches("\\d+")) {
            return Result.error(400, "Invalid user ID in session");
        }
        Long userId = Long.valueOf(userIdStr);
        return Result.success(workspaceAppService.getIndexes(userId, connectionId, schemaName, tableName));
    }

    @GetMapping("/ddl")
    public Result<String> getTableDDL(@RequestParam Long connectionId,
                                      @RequestParam(required = false) String schemaName,
                                      @RequestParam String tableName) {
        String userIdStr = (String) StpUtil.getLoginId();
        if (userIdStr == null || !userIdStr.matches("\\d+")) {
            return Result.error(400, "Invalid user ID in session");
        }
        Long userId = Long.valueOf(userIdStr);
        return Result.success(workspaceAppService.getTableDDL(userId, connectionId, schemaName, tableName));
    }
}