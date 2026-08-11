package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sql.logic.engine.infrastructure.dao.AppDefinitionDao;
import com.sql.logic.engine.infrastructure.po.AppDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/apps")
public class AppController {

    private final AppDefinitionDao appDao;

    public AppController(AppDefinitionDao appDao) {
        this.appDao = appDao;
    }

    private Long currentUserId() {
        String id = (String) StpUtil.getLoginId();
        if (id == null || !id.matches("\\d+")) return null;
        return Long.valueOf(id);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listApps() {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.status(401).build();
        var list = appDao.selectList(new LambdaQueryWrapper<AppDefinition>().eq(AppDefinition::getUserId, userId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var app : list) {
            result.add(appToMap(app));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getApp(@PathVariable Long id) {
        var app = appDao.selectById(id);
        if (app == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(appToMap(app));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createApp(@RequestBody Map<String, Object> body) {
        Long userId = currentUserId();
        if (userId == null) return ResponseEntity.status(401).build();

        AppDefinition app = new AppDefinition();
        app.setName((String) body.getOrDefault("name", "Untitled App"));
        app.setDescription((String) body.getOrDefault("description", ""));
        app.setTeamMode((String) body.getOrDefault("teamMode", "auto_plan"));
        app.setTeamContext((String) body.getOrDefault("teamContext", null));
        app.setAgentDetails((String) body.getOrDefault("agentDetails", null));
        app.setPublished(0);
        app.setUserId(userId);
        app.setWorkspaceId(body.containsKey("workspaceId") ? ((Number) body.get("workspaceId")).longValue() : null);
        appDao.insert(app);
        return ResponseEntity.ok(appToMap(app));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> updateApp(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        var app = appDao.selectById(id);
        if (app == null) return ResponseEntity.notFound().build();

        if (body.containsKey("name")) app.setName((String) body.get("name"));
        if (body.containsKey("description")) app.setDescription((String) body.get("description"));
        if (body.containsKey("teamMode")) app.setTeamMode((String) body.get("teamMode"));
        if (body.containsKey("teamContext")) app.setTeamContext((String) body.get("teamContext"));
        if (body.containsKey("agentDetails")) app.setAgentDetails((String) body.get("agentDetails"));
        if (body.containsKey("published")) app.setPublished(((Number) body.get("published")).intValue());
        appDao.updateById(app);
        return ResponseEntity.ok(appToMap(app));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApp(@PathVariable Long id) {
        appDao.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> publishApp(@PathVariable Long id) {
        var app = appDao.selectById(id);
        if (app == null) return ResponseEntity.notFound().build();
        app.setPublished(1);
        appDao.updateById(app);
        return ResponseEntity.ok(appToMap(app));
    }

    private Map<String, Object> appToMap(AppDefinition app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", app.getId());
        m.put("name", app.getName());
        m.put("description", app.getDescription());
        m.put("teamMode", app.getTeamMode());
        m.put("teamContext", app.getTeamContext());
        m.put("agentDetails", app.getAgentDetails());
        m.put("published", app.getPublished());
        m.put("userId", app.getUserId());
        return m;
    }
}
