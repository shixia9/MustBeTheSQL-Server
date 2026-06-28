package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.dto.ForeignKeyRelation;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.dialect.model.ColumnDTO;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for sampling column data values from database tables.
 * <p>
 * Executes SELECT DISTINCT queries on target columns to provide the LLM
 * with "data sense" — actual values that exist in the database, preventing
 * hallucinated WHERE clauses with impossible values.
 * <p>
 * Output format matches the reference project's Schema.buildTablePrompt pattern:
 * <pre>
 * # Table: customers
 * [
 * (id: BIGINT, Primary Key),
 * (name: VARCHAR, Examples: [Alice, Bob, Carol]),
 * ]
 * </pre>
 */
@Service
public class ColumnSampleService {

    private static final Logger log = LoggerFactory.getLogger(ColumnSampleService.class);

    /** Maximum number of columns to sample per table. */
    private static final int DEFAULT_MAX_COLUMNS = 10;
    /** Maximum number of distinct values per column. */
    private static final int DEFAULT_SAMPLE_LIMIT = 3;
    /** Maximum total character length of the sampling output. */
    private static final int MAX_OUTPUT_CHARS = 2000;

    /** Column types that typically don't support DISTINCT or are not useful for sampling. */
    private static final Set<String> UNSAMPLABLE_TYPES = Set.of(
            "BLOB", "LONGBLOB", "TINYBLOB", "MEDIUMBLOB",
            "BYTEA",  // PostgreSQL binary
            "GEOMETRY", "POINT", "LINESTRING", "POLYGON",
            "JSON", "JSONB"
    );

    private final DatabaseAppService databaseAppService;
    private final DatabaseMetaDataService databaseMetaDataService;
    private final DbConnectionConfDao dbConnectionConfDao;

    public ColumnSampleService(DatabaseAppService databaseAppService,
                                DatabaseMetaDataService databaseMetaDataService,
                                DbConnectionConfDao dbConnectionConfDao) {
        this.databaseAppService = databaseAppService;
        this.databaseMetaDataService = databaseMetaDataService;
        this.dbConnectionConfDao = dbConnectionConfDao;
    }

    /**
     * Build a formatted schema prompt string with data samples for the specified tables.
     * Includes table column definitions with sampled distinct values.
     *
     * @param connectionId database connection ID
     * @param tableNames   list of table names to sample
     * @return formatted schema string with sampled data, or empty string if no tables
     */
    public String getColumnSamples(Long connectionId, List<String> tableNames, String schemaName) {
        return getColumnSamples(connectionId, tableNames, schemaName, DEFAULT_MAX_COLUMNS, DEFAULT_SAMPLE_LIMIT);
    }

    public String getColumnSamples(Long connectionId, List<String> tableNames) {
        return getColumnSamples(connectionId, tableNames, null, DEFAULT_MAX_COLUMNS, DEFAULT_SAMPLE_LIMIT);
    }

    /**
     * Build a formatted schema prompt string with data samples for the specified tables.
     *
     * @param connectionId       database connection ID
     * @param tableNames         list of table names to sample
     * @param maxColumnsPerTable max columns to include per table
     * @param sampleLimit        max distinct values per column
     * @return formatted schema string with sampled data
     */
    public String getColumnSamples(Long connectionId, List<String> tableNames,
                                   String schemaName, int maxColumnsPerTable, int sampleLimit) {
        if (tableNames == null || tableNames.isEmpty() || connectionId == null) {
            return "";
        }

        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf == null) return "";

        boolean isPostgreSQL = conf.getDbType() != null && conf.getDbType().toLowerCase().contains("postgres");
        String dbName = conf.getDbName() != null ? conf.getDbName() : "";
        StringBuilder result = new StringBuilder();
        result.append("【DB_ID】 ").append(dbName).append("\n");

        try (Connection conn = databaseAppService.getConnection(connectionId)) {
            for (String tableName : tableNames) {
                String tablePrompt = buildTablePrompt(conn, connectionId, tableName,
                        isPostgreSQL, maxColumnsPerTable, sampleLimit);
                if (tablePrompt != null) {
                    result.append(tablePrompt).append("\n");
                }
                // Respect overall output budget
                if (result.length() > MAX_OUTPUT_CHARS) {
                    log.debug("[ColumnSampleService] Output exceeded {} chars, truncating remaining tables",
                            MAX_OUTPUT_CHARS);
                    break;
                }
            }
        } catch (SQLException e) {
            log.error("[ColumnSampleService] Failed to get connection for sampling: {}", e.getMessage());
            return result.toString();
        }

        return result.toString();
    }

    /**
     * Build a single table prompt with column definitions and sampled data.
     */
    private String buildTablePrompt(Connection conn, Long connectionId, String tableName,
                                    boolean isPostgreSQL, int maxColumns, int sampleLimit) {
        List<ColumnDTO> columns;
        try {
            columns = databaseMetaDataService.getTableColumns(connectionId, tableName);
        } catch (Exception e) {
            log.warn("[ColumnSampleService] Failed to get columns for table {}: {}", tableName, e.getMessage());
            return null;
        }

        if (columns == null || columns.isEmpty()) {
            return null;
        }

        // Limit columns to maxColumns
        List<ColumnDTO> sampledColumns = columns.size() > maxColumns
                ? columns.subList(0, maxColumns) : columns;

        StringBuilder sb = new StringBuilder();
        sb.append("# Table: ").append(tableName).append("\n[\n");

        Set<String> primaryKeys = columns.stream()
                .filter(c -> Boolean.TRUE.equals(c.getPrimaryKey()))
                .map(ColumnDTO::getName)
                .collect(Collectors.toSet());

        for (ColumnDTO column : sampledColumns) {
            sb.append("(").append(column.getName()).append(": ").append(simplifyType(column.getDataType()));

            if (primaryKeys.contains(column.getName())) {
                sb.append(", Primary Key");
            }

            // Attempt data sampling for this column (skip unsamplable types)
            if (!UNSAMPLABLE_TYPES.contains(column.getDataType() != null ? column.getDataType().toUpperCase() : "")) {
                List<String> samples = fetchDistinctValues(conn, tableName, column.getName(),
                        sampleLimit, isPostgreSQL);
                if (!samples.isEmpty()) {
                    sb.append(", Examples: [").append(String.join(", ", samples)).append("]");
                }
            }

            sb.append("),\n");
        }

        sb.append("]\n");
        return sb.toString();
    }

    /**
     * Fetch distinct values for a column using SELECT DISTINCT ... LIMIT.
     * Returns up to sampleLimit distinct non-null values.
     */
    private List<String> fetchDistinctValues(Connection conn, String tableName, String columnName,
                                             int sampleLimit, boolean isPostgreSQL) {
        String quotedColumn = isPostgreSQL
                ? "\"" + columnName.replace("\"", "\"\"") + "\""
                : "`" + columnName.replace("`", "``") + "`";
        String quotedTable = isPostgreSQL
                ? "\"" + tableName.replace("\"", "\"\"") + "\""
                : "`" + tableName.replace("`", "``") + "`";

        String sql = String.format("SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL LIMIT %d",
                quotedColumn, quotedTable, quotedColumn, sampleLimit);

        List<String> values = new ArrayList<>();
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(5); // 5 second timeout per column query
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isBlank()) {
                        // Truncate very long values
                        values.add(value.length() > 50 ? value.substring(0, 47) + "..." : value);
                    }
                }
            }
        } catch (SQLException e) {
            // Some column types don't support DISTINCT; skip silently
            log.debug("[ColumnSampleService] DISTINCT sampling failed for {}.{}: {}",
                    tableName, columnName, e.getMessage());
        }
        return values;
    }

    /**
     * Simplify a column type for display in the prompt.
     * E.g., "VARCHAR(255)" -> "VARCHAR", "INT UNSIGNED" -> "INT".
     */
    private String simplifyType(String dataType) {
        if (dataType == null) return "UNKNOWN";
        // Remove length/precision suffix like (255), (10,2)
        String simplified = dataType.replaceAll("\\(\\d+(,\\s*\\d+)?\\)", "");
        // Remove UNSIGNED/ZEROFILL qualifiers
        simplified = simplified.replace(" UNSIGNED", "").replace(" ZEROFILL", "").trim();
        return simplified;
    }

    /**
     * Build FK expression strings for the schema prompt.
     * Format: sourceTable.sourceColumn = targetTable.targetColumn
     */
    public String buildForeignKeyExpressions(List<ForeignKeyRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return "";
        }
        return relations.stream()
                .map(ForeignKeyRelation::toExpression)
                .collect(Collectors.joining("\n"));
    }
}