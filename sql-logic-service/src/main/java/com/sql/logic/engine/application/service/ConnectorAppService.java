package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.common.dto.ActiveConnectorCreateRequest;
import com.sql.logic.engine.common.dto.ActiveConnectorResponse;
import com.sql.logic.engine.common.dto.ActiveConnectorUpdateRequest;
import com.sql.logic.engine.common.dto.ConnectorTemplateCreateRequest;
import com.sql.logic.engine.common.dto.ConnectorTemplateResponse;
import com.sql.logic.engine.common.dto.ConnectorTemplateUpdateRequest;
import com.sql.logic.engine.infrastructure.dao.ActiveConnectorDao;
import com.sql.logic.engine.infrastructure.dao.ConnectorTemplateDao;
import com.sql.logic.engine.infrastructure.po.ActiveConnector;
import com.sql.logic.engine.infrastructure.po.ConnectorTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * CRUD for connector templates and active connectors.
 * Tenant scoping is enforced on every read/write by userId ownership validation.
 */
@Service
public class ConnectorAppService {

    private final ConnectorTemplateDao connectorTemplateDao;
    private final ActiveConnectorDao activeConnectorDao;

    public ConnectorAppService(ConnectorTemplateDao connectorTemplateDao,
                               ActiveConnectorDao activeConnectorDao) {
        this.connectorTemplateDao = connectorTemplateDao;
        this.activeConnectorDao = activeConnectorDao;
    }

    // ── Connector Templates ──────────────────────────────────────────

    public List<ConnectorTemplateResponse> listTemplates(Long userId) {
        QueryWrapper<ConnectorTemplate> q = new QueryWrapper<>();
        q.eq("user_id", userId).orderByDesc("update_time");
        return connectorTemplateDao.selectList(q).stream().map(this::toTemplateResponse).toList();
    }

    public ConnectorTemplateResponse createTemplate(Long userId, ConnectorTemplateCreateRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Connector name is required");
        }
        ConnectorTemplate row = new ConnectorTemplate();
        row.setUserId(userId);
        row.setName(request.getName());
        row.setConnectorType(request.getConnectorType());
        row.setConfig(request.getConfig());
        row.setDescription(request.getDescription());
        row.setStatus(1);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        connectorTemplateDao.insert(row);
        return toTemplateResponse(row);
    }

    public ConnectorTemplateResponse updateTemplate(Long userId, ConnectorTemplateUpdateRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException("Template id is required");
        }
        ConnectorTemplate row = connectorTemplateDao.selectById(request.getId());
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Connector template not found or does not belong to this user");
        }
        if (request.getName() != null) row.setName(request.getName());
        if (request.getConnectorType() != null) row.setConnectorType(request.getConnectorType());
        if (request.getConfig() != null) row.setConfig(request.getConfig());
        if (request.getDescription() != null) row.setDescription(request.getDescription());
        if (request.getStatus() != null) row.setStatus(request.getStatus());
        row.setUpdateTime(new Date());
        connectorTemplateDao.updateById(row);
        return toTemplateResponse(row);
    }

    public void deleteTemplate(Long userId, Long templateId) {
        ConnectorTemplate row = connectorTemplateDao.selectById(templateId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Connector template not found or does not belong to this user");
        }
        connectorTemplateDao.deleteById(templateId);
    }

    // ── Active Connectors ───────────────────────────────────────────

    public List<ActiveConnectorResponse> listActive(Long userId) {
        QueryWrapper<ActiveConnector> q = new QueryWrapper<>();
        q.eq("user_id", userId).orderByDesc("create_time");
        return activeConnectorDao.selectList(q).stream().map(this::toActiveResponse).toList();
    }

    public ActiveConnectorResponse createActive(Long userId, ActiveConnectorCreateRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Active connector name is required");
        }
        ActiveConnector row = new ActiveConnector();
        row.setUserId(userId);
        row.setTemplateId(request.getTemplateId());
        row.setConnectionId(request.getConnectionId());
        row.setName(request.getName());
        row.setStatus(1);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        activeConnectorDao.insert(row);
        return toActiveResponse(row);
    }

    public void deleteActive(Long userId, Long activeId) {
        ActiveConnector row = activeConnectorDao.selectById(activeId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Active connector not found or does not belong to this user");
        }
        activeConnectorDao.deleteById(activeId);
    }

    public ActiveConnectorResponse updateActive(Long userId, ActiveConnectorUpdateRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException("Active connector id is required");
        }
        ActiveConnector row = activeConnectorDao.selectById(request.getId());
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Active connector not found or does not belong to this user");
        }
        if (request.getName() != null) row.setName(request.getName());
        if (request.getTemplateId() != null) row.setTemplateId(request.getTemplateId());
        if (request.getConnectionId() != null) row.setConnectionId(request.getConnectionId());
        row.setUpdateTime(new Date());
        activeConnectorDao.updateById(row);
        return toActiveResponse(row);
    }

    // ── Mappers ──────────────────────────────────────────────────────

    private ConnectorTemplateResponse toTemplateResponse(ConnectorTemplate row) {
        ConnectorTemplateResponse r = new ConnectorTemplateResponse();
        r.setId(row.getId());
        r.setName(row.getName());
        r.setConnectorType(row.getConnectorType());
        r.setConfig(row.getConfig());
        r.setDescription(row.getDescription());
        r.setStatus(row.getStatus());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        r.setCreateTime(row.getCreateTime() != null ? sdf.format(row.getCreateTime()) : null);
        r.setUpdateTime(row.getUpdateTime() != null ? sdf.format(row.getUpdateTime()) : null);
        return r;
    }

    private ActiveConnectorResponse toActiveResponse(ActiveConnector row) {
        ActiveConnectorResponse r = new ActiveConnectorResponse();
        r.setId(row.getId());
        r.setTemplateId(row.getTemplateId());
        r.setConnectionId(row.getConnectionId());
        r.setName(row.getName());
        r.setStatus(row.getStatus());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        r.setCreateTime(row.getCreateTime() != null ? sdf.format(row.getCreateTime()) : null);
        r.setUpdateTime(row.getUpdateTime() != null ? sdf.format(row.getUpdateTime()) : null);
        return r;
    }
}
