package com.sql.logic.engine.infrastructure.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 5 — manually build {@link PgVectorStore} against the dedicated PG
 * {@link JdbcTemplate} so the vector store NEVER binds to the MySQL business
 * datasource (the default auto-configured {@link JdbcTemplate}).
 * <p>
 * {@link PgVectorStoreProperties} still binds to {@code spring.ai.vectorstore.pgvector.*}
 * under Nacos, so dimensions/HNSW/cosine/initialize-schema stay in one place. We
 * only replace the JdbcTemplate Spring would otherwise have auto-selected (the
 * MySQL one). {@code PgVectorStoreAutoConfiguration} is excluded on the
 * {@code @SpringBootApplication} to avoid a duplicate {@link VectorStore} bean.
 * <p>
 * {@code EmbeddingModel} is auto-configured by the OpenAI starter from
 * {@code spring.ai.openai.embedding.*} (text-embedding-3-small, 1536-dim).
 */
@Configuration
@EnableConfigurationProperties(PgVectorStoreProperties.class)
@ConditionalOnProperty(prefix = "app.pgvector.datasource", name = "url")
public class PgVectorStoreConfig {

    @Bean
    public VectorStore pgVectorStore(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            EmbeddingModel embeddingModel,
            PgVectorStoreProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<VectorStoreObservationConvention> observationConvention) {
        return PgVectorStore.builder(pgVectorJdbcTemplate, embeddingModel)
                .schemaName(properties.getSchemaName())
                .vectorTableName(properties.getTableName())
                .idType(properties.getIdType())
                .dimensions(properties.getDimensions())
                .distanceType(properties.getDistanceType())
                .indexType(properties.getIndexType())
                .initializeSchema(properties.isInitializeSchema())
                .vectorTableValidationsEnabled(properties.isSchemaValidation())
                .removeExistingVectorStoreTable(properties.isRemoveExistingVectorStoreTable())
                .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
                .batchingStrategy(pgVectorStoreBatchingStrategy())
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .customObservationConvention(observationConvention.getIfAvailable(() -> null))
                .build();
    }

    @Bean
    public BatchingStrategy pgVectorStoreBatchingStrategy() {
        return new TokenCountBatchingStrategy();
    }
}