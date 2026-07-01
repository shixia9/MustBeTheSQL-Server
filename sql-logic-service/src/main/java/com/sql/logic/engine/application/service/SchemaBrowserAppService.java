package com.sql.logic.engine.application.service;

import com.sql.logic.engine.infrastructure.dialect.DialectFactory;
import com.sql.logic.engine.infrastructure.dialect.MetaData;
import com.sql.logic.engine.infrastructure.dialect.model.ColumnDTO;
import com.sql.logic.engine.infrastructure.dialect.model.IndexDTO;
import com.sql.logic.engine.infrastructure.dialect.model.SchemaDTO;
import com.sql.logic.engine.infrastructure.dialect.model.TableDTO;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;

/**
 * Schema browser service — fetches live database metadata (schemas, tables, columns,
 * indexes, DDL) from a user's database connection. Renamed from {@code WorkspaceAppService}
 * in Phase A to avoid collision with the multi-tenant workspace management service.
 */
@Service
public class SchemaBrowserAppService {

    private final DatabaseAppService databaseAppService;
    private final DialectFactory dialectFactory;
    private final DbConnectionConfDao dbConnectionConfDao;

    public SchemaBrowserAppService(DatabaseAppService databaseAppService, DialectFactory dialectFactory, DbConnectionConfDao dbConnectionConfDao) {
        this.databaseAppService = databaseAppService;
        this.dialectFactory = dialectFactory;
        this.dbConnectionConfDao = dbConnectionConfDao;
    }

    private MetaData getMetaData(Long connectionId) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) {
            throw new IllegalArgumentException("Connection not found");
        }
        return dialectFactory.getMetaData(conf.getDbType());
    }

    public List<SchemaDTO> getSchemas(Long userId, Long connectionId) {
        databaseAppService.assertUserCanAccessConnection(userId, connectionId);
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            return getMetaData(connectionId).schemas(conn);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch schemas: " + e.getMessage(), e);
        }
    }

    public List<TableDTO> getTables(Long userId, Long connectionId, String schemaName) {
        databaseAppService.assertUserCanAccessConnection(userId, connectionId);
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            return getMetaData(connectionId).tables(conn, schemaName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch tables: " + e.getMessage(), e);
        }
    }

    public List<ColumnDTO> getColumns(Long userId, Long connectionId, String schemaName, String tableName) {
        databaseAppService.assertUserCanAccessConnection(userId, connectionId);
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            return getMetaData(connectionId).columns(conn, schemaName, tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch columns: " + e.getMessage(), e);
        }
    }

    public List<IndexDTO> getIndexes(Long userId, Long connectionId, String schemaName, String tableName) {
        databaseAppService.assertUserCanAccessConnection(userId, connectionId);
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            return getMetaData(connectionId).indexes(conn, schemaName, tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch indexes: " + e.getMessage(), e);
        }
    }

    public String getTableDDL(Long userId, Long connectionId, String schemaName, String tableName) {
        databaseAppService.assertUserCanAccessConnection(userId, connectionId);
        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            return getMetaData(connectionId).tableDDL(conn, schemaName, tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch DDL: " + e.getMessage(), e);
        }
    }
}
