package com.sql.logic.engine;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Excludes {@link PgVectorStoreAutoConfiguration} because Phase 5 wires
 * {@code PgVectorStore} manually against the dedicated PG {@code JdbcTemplate}
 * — the auto-config would otherwise bind the vector store to the default
 * (MySQL business) JdbcTemplate. See {@code PgVectorStoreConfig}.
 */
@SpringBootApplication(exclude = { PgVectorStoreAutoConfiguration.class })
@EnableDiscoveryClient
@EnableAsync
@EnableDubbo
public class SqlLogicEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlLogicEngineApplication.class, args);
    }

}