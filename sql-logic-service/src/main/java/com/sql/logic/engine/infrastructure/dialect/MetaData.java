package com.sql.logic.engine.infrastructure.dialect;

import com.sql.logic.engine.infrastructure.dialect.model.ColumnDTO;
import com.sql.logic.engine.infrastructure.dialect.model.IndexDTO;
import com.sql.logic.engine.infrastructure.dialect.model.SchemaDTO;
import com.sql.logic.engine.infrastructure.dialect.model.TableDTO;

import java.sql.Connection;
import java.util.List;

public interface MetaData {

    String dbType();

    List<SchemaDTO> schemas(Connection connection);

    List<TableDTO> tables(Connection connection, String schemaName);

    List<ColumnDTO> columns(Connection connection, String schemaName, String tableName);

    List<IndexDTO> indexes(Connection connection, String schemaName, String tableName);

    String tableDDL(Connection connection, String schemaName, String tableName);
}
