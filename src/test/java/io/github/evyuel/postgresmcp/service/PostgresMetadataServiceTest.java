package io.github.evyuel.postgresmcp.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.evyuel.postgresmcp.config.MetadataProperties;
import io.github.evyuel.postgresmcp.exception.MetadataException;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgresMetadataServiceTest {
    @Test
    void rejectsSchemaOutsideAllowlistBeforeQueryingCatalog() {
        var repository = mock(PostgresCatalogRepository.class);
        var properties = new MetadataProperties();
        properties.setAllowedSchemas(List.of("app"));
        var service = new PostgresMetadataService(repository, properties);

        assertThatThrownBy(() -> service.listTables("private"))
            .isInstanceOf(MetadataException.class)
            .hasMessage("Schema is not in the configured allowlist");
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBlankIdentifiers() {
        var repository = mock(PostgresCatalogRepository.class);
        var service = new PostgresMetadataService(repository, new MetadataProperties());

        assertThatThrownBy(() -> service.describeTable(" ", "users"))
            .isInstanceOf(MetadataException.class)
            .hasMessage("Invalid schema name");
    }
}

