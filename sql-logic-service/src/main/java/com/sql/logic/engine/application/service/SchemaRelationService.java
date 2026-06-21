package com.sql.logic.engine.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.sql.logic.engine.domain.agent.dto.ForeignKeyRelation;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for extracting and managing foreign key relationships from database metadata.
 * <p>
 * Queries INFORMATION_SCHEMA.KEY_COLUMN_USAGE to retrieve all FK relationships
 * for a given database connection. Results are cached per connectionId with a 30-minute TTL.
 * Supports both MySQL and PostgreSQL dialects.
 */
@Service
public class SchemaRelationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaRelationService.class);

    private final DatabaseAppService databaseAppService;
    private final DbConnectionConfDao dbConnectionConfDao;

    /**
     * Cache: connectionId -> List of ForeignKeyRelation.
     * 30-minute TTL, matching the DDL cache pattern in DatabaseMetaDataService.
     */
    @Resource(name = "fkRelationCache")
    private Cache<Long, List<ForeignKeyRelation>> fkRelationCache;

    public SchemaRelationService(DatabaseAppService databaseAppService,
                                  DbConnectionConfDao dbConnectionConfDao) {
        this.databaseAppService = databaseAppService;
        this.dbConnectionConfDao = dbConnectionConfDao;
    }

    /**
     * Retrieve all foreign key relationships for a database connection.
     * Uses a single INFORMATION_SCHEMA query (not N+1 getImportedKeys calls).
     * Results are cached per connectionId.
     *
     * @param connectionId the database connection ID
     * @return list of ForeignKeyRelation representing all FK constraints
     */
    public List<ForeignKeyRelation> getForeignKeyRelations(Long connectionId) {
        return fkRelationCache.get(connectionId, this::fetchForeignKeyRelations);
    }

    /**
     * Expand a core set of table names by following foreign key relationships.
     * Returns the union of coreTables plus any table reachable via one FK hop.
     * <p>
     * For example, if coreTables = {"orders"} and there is an FK:
     * orders.customer_id -> customers.id, then customers is added to the result.
     *
     * @param connectionId the database connection ID
     * @param coreTables   the initial set of table names
     * @return expanded set including FK-connected tables
     */
    public Set<String> expandWithJoinTables(Long connectionId, Set<String> coreTables) {
        if (coreTables == null || coreTables.isEmpty()) {
            return Collections.emptySet();
        }
        List<ForeignKeyRelation> allRelations = getForeignKeyRelations(connectionId);
        Set<String> expanded = new LinkedHashSet<>(coreTables);

        for (ForeignKeyRelation fk : allRelations) {
            if (coreTables.contains(fk.getSourceTable()) || coreTables.contains(fk.getTargetTable())) {
                expanded.add(fk.getSourceTable());
                expanded.add(fk.getTargetTable());
            }
        }
        return expanded;
    }

    /**
     * Filter FK relations to only those involving the given table names.
     * A relation is included if either its source or target table is in the set.
     *
     * @param allRelations all FK relations for the database
     * @param tableNames   the set of tables to filter by
     * @return filtered list of FK relations
     */
    public List<ForeignKeyRelation> filterRelationsForTables(List<ForeignKeyRelation> allRelations,
                                                             Set<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return Collections.emptyList();
        }
        return allRelations.stream()
                .filter(fk -> tableNames.contains(fk.getSourceTable()) || tableNames.contains(fk.getTargetTable()))
                .collect(Collectors.toList());
    }

    /**
     * Clear the FK relation cache for a specific connection.
     */
    public void clearCache(Long connectionId) {
        fkRelationCache.invalidate(connectionId);
    }

    /**
     * Fetch FK relations from the database via INFORMATION_SCHEMA.
     * Uses a single query rather than N+1 getImportedKeys() calls.
     */
    private List<ForeignKeyRelation> fetchForeignKeyRelations(Long connectionId) {
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) {
            throw new IllegalArgumentException("Connection not found: " + connectionId);
        }

        String schemaName = conf.getDbName(); // For MySQL, TABLE_SCHEMA = database name
        boolean isPostgreSQL = conf.getDbType() != null && conf.getDbType().toLowerCase().contains("postgres");

        // For PostgreSQL, the schema name might differ from dbName
        // (typically "public" for the default schema)
        if (isPostgreSQL) {
            // Use dbName as schema for PostgreSQL, fallback to "public"
            // Most PostgreSQL setups use "public" as the default schema
            schemaName = conf.getDbName() != null ? conf.getDbName() : "public";
        }

        String sql = "SELECT kcu.TABLE_NAME, kcu.COLUMN_NAME, " +
                "kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME " +
                "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu " +
                "WHERE kcu.TABLE_SCHEMA = ? " +
                "AND kcu.REFERENCED_TABLE_NAME IS NOT NULL " +
                "ORDER BY kcu.TABLE_NAME, kcu.COLUMN_NAME";

        List<ForeignKeyRelation> relations = new ArrayList<>();

        try (Connection conn = databaseAppService.getConnection(connectionId);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String sourceTable = rs.getString("TABLE_NAME");
                    String sourceColumn = rs.getString("COLUMN_NAME");
                    String targetTable = rs.getString("REFERENCED_TABLE_NAME");
                    String targetColumn = rs.getString("REFERENCED_COLUMN_NAME");

                    if (sourceTable != null && sourceColumn != null &&
                            targetTable != null && targetColumn != null) {
                        relations.add(new ForeignKeyRelation(
                                sourceTable, sourceColumn, targetTable, targetColumn));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("[SchemaRelationService] Failed to fetch FK relations for connectionId={}, " +
                    "schema={}: {}", connectionId, schemaName, e.getMessage());
            // Return empty list rather than throwing — some databases may not have FK constraints
            return Collections.emptyList();
        }

        log.info("[SchemaRelationService] Fetched {} FK relations for connectionId={}, schema={}",
                relations.size(), connectionId, schemaName);
        return relations;
    }
}