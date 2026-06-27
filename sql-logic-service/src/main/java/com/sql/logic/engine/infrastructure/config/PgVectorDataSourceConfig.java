package com.sql.logic.engine.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Phase 5 — dedicated PostgreSQL+pgvector datasource for the RAG vector store.
 * <p>
 * <b>Critical:</b> this config explicitly declares BOTH the MySQL and the PG DataSource
 * beans, with MySQL as {@code @Primary}, so MyBatis-Plus auto-configuration always
 * binds to MySQL (where all business tables live). The PG DataSource is non-primary
 * and used exclusively by the manual {@code PgVectorStore} bean.
 * <p>
 * The MySQL {@link DataSource} is built from {@code spring.datasource.*} (Nacos-managed,
 * jdbc:mysql:…) and replaces the Spring Boot auto-configured DataSource that would
 * otherwise conflict when multiple beans of type {@code DataSource} are present.
 * <p>
 * The PG {@link DataSource} binds to {@code app.pgvector.datasource.*}.
 */
@Configuration
public class PgVectorDataSourceConfig {

    // ======================== MySQL (Primary — MyBatis-Plus) ========================

    /**
     * MySQL business DataSource — the ONLY {@code @Primary} DataSource.
     * Built via Spring Boot's {@link DataSourceProperties} which properly maps
     * {@code spring.datasource.url} → {@link HikariConfig#setJdbcUrl}.
     */
    @Primary
    @Bean(name = "mysqlDataSource")
    public DataSource mysqlDataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .build();
    }

    // ======================== PostgreSQL (Vector Store) ========================

    @ConditionalOnProperty(prefix = "app.pgvector.datasource", name = "url")
    @Bean(name = "pgVectorDataSource")
    public DataSource pgVectorDataSource(
            @Value("${app.pgvector.datasource.url}") String url,
            @Value("${app.pgvector.datasource.username}") String username,
            @Value("${app.pgvector.datasource.password}") String password,
            @Value("${app.pgvector.datasource.driver-class-name:org.postgresql.Driver}") String driverClassName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClassName);
        ds.setPoolName("HikariPool-PgVector");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(1);
        return ds;
    }

    @ConditionalOnProperty(prefix = "app.pgvector.datasource", name = "url")
    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }
}