package com.sql.logic.engine.infrastructure.dialect;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DialectFactory {

    private final Map<String, MetaData> dialectMap = new HashMap<>();

    public DialectFactory(List<MetaData> dialects) {
        for (MetaData dialect : dialects) {
            dialectMap.put(dialect.dbType().toLowerCase(), dialect);
        }
    }

    public MetaData getMetaData(String dbType) {
        MetaData metaData = dialectMap.get(dbType.toLowerCase());
        if (metaData == null) {
            throw new IllegalArgumentException("Unsupported database type for Dialect: " + dbType);
        }
        return metaData;
    }
}
