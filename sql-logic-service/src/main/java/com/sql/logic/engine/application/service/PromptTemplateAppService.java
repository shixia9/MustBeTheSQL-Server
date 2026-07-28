package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.common.dto.PromptTemplateCreateRequest;
import com.sql.logic.engine.common.dto.PromptTemplateResponse;
import com.sql.logic.engine.common.dto.PromptTemplateUpdateRequest;
import com.sql.logic.engine.infrastructure.dao.PromptTemplateDao;
import com.sql.logic.engine.infrastructure.po.PromptTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * CRUD for user-managed reusable prompt templates.
 * Tenant scoping is enforced on every read/write by userId ownership validation.
 */
@Service
public class PromptTemplateAppService {

    private final PromptTemplateDao promptTemplateDao;

    public PromptTemplateAppService(PromptTemplateDao promptTemplateDao) {
        this.promptTemplateDao = promptTemplateDao;
    }

    public List<PromptTemplateResponse> list(Long userId) {
        QueryWrapper<PromptTemplate> q = new QueryWrapper<>();
        q.eq("user_id", userId).orderByDesc("update_time");
        return promptTemplateDao.selectList(q).stream().map(this::toResponse).toList();
    }

    public PromptTemplateResponse create(Long userId, PromptTemplateCreateRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Prompt name is required");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new IllegalArgumentException("Prompt content is required");
        }
        PromptTemplate row = new PromptTemplate();
        row.setUserId(userId);
        row.setName(request.getName());
        row.setContent(request.getContent());
        row.setDescription(request.getDescription());
        row.setStatus(1);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        promptTemplateDao.insert(row);
        return toResponse(row);
    }

    public PromptTemplateResponse update(Long userId, PromptTemplateUpdateRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException("Prompt id is required");
        }
        PromptTemplate row = promptTemplateDao.selectById(request.getId());
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Prompt not found or does not belong to this user");
        }
        if (request.getName() != null) row.setName(request.getName());
        if (request.getContent() != null) row.setContent(request.getContent());
        if (request.getDescription() != null) row.setDescription(request.getDescription());
        if (request.getStatus() != null) row.setStatus(request.getStatus());
        row.setUpdateTime(new Date());
        promptTemplateDao.updateById(row);
        return toResponse(row);
    }

    public void delete(Long userId, Long promptId) {
        PromptTemplate row = promptTemplateDao.selectById(promptId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Prompt not found or does not belong to this user");
        }
        promptTemplateDao.deleteById(promptId);
    }

    private PromptTemplateResponse toResponse(PromptTemplate row) {
        PromptTemplateResponse r = new PromptTemplateResponse();
        r.setId(row.getId());
        r.setName(row.getName());
        r.setContent(row.getContent());
        r.setDescription(row.getDescription());
        r.setStatus(row.getStatus());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        r.setCreateTime(row.getCreateTime() != null ? sdf.format(row.getCreateTime()) : null);
        r.setUpdateTime(row.getUpdateTime() != null ? sdf.format(row.getUpdateTime()) : null);
        return r;
    }
}
