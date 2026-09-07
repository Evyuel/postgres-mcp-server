package io.github.evyuel.postgresmcp.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class JdbcConfiguration {
    @Bean
    NamedParameterJdbcTemplate metadataJdbcTemplate(DataSource dataSource, MetadataProperties properties) {
        var template = new NamedParameterJdbcTemplate(dataSource);
        template.getJdbcTemplate().setQueryTimeout(properties.getQueryTimeoutSeconds());
        template.getJdbcTemplate().setMaxRows(properties.getMaxObjects() + 1);
        return template;
    }
}

