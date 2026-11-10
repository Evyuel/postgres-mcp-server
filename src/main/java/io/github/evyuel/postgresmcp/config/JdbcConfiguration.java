package io.github.evyuel.postgresmcp.config;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class JdbcConfiguration {
    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    NamedParameterJdbcTemplate metadataJdbcTemplate(@Qualifier("dataSource") DataSource dataSource,
                                                     MetadataProperties properties) {
        var template = new NamedParameterJdbcTemplate(dataSource);
        template.getJdbcTemplate().setQueryTimeout(properties.getQueryTimeoutSeconds());
        return template;
    }

    @Bean(name = "diagnosticsDataSource", destroyMethod = "close")
    HikariDataSource diagnosticsDataSource(@Qualifier("dataSource") DataSource dataSource,
                                           DiagnosticsProperties properties) {
        if (!(dataSource instanceof HikariDataSource metadata)) {
            throw new IllegalStateException("Diagnostics requires the Spring Boot Hikari datasource");
        }
        var config = new HikariConfig();
        config.setJdbcUrl(metadata.getJdbcUrl());
        config.setUsername(metadata.getUsername());
        config.setPassword(metadata.getPassword());
        if (metadata.getDriverClassName() != null && !metadata.getDriverClassName().isBlank()) {
            config.setDriverClassName(metadata.getDriverClassName());
        }
        config.setDataSourceProperties(metadata.getDataSourceProperties());
        config.setPoolName("postgres-diagnostics-pool");
        config.setMaximumPoolSize(properties.getMaxConcurrent());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(metadata.getConnectionTimeout());
        config.setValidationTimeout(metadata.getValidationTimeout());
        config.setReadOnly(true);
        config.setAutoCommit(false);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("ApplicationName", "postgres-mcp-server-diagnostics");
        return new HikariDataSource(config);
    }
}

