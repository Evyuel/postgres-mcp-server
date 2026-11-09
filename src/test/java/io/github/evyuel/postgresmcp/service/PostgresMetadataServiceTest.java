package io.github.evyuel.postgresmcp.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.evyuel.postgresmcp.config.MetadataProperties;
import io.github.evyuel.postgresmcp.exception.MetadataException;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository;
import org.junit.jupiter.api.Test;

class PostgresMetadataServiceTest {
    @Test
    void rejectsUnknownSchema() {
        var repository = mock(PostgresCatalogRepository.class);
        var properties = new MetadataProperties();
        var service = new PostgresMetadataService(repository, properties, mock(PostgresTypeService.class));

        assertThatThrownBy(() -> service.listTables("private"))
            .isInstanceOf(MetadataException.class)
            .hasMessage("Schema is not visible or does not exist");
    }

    @Test
    void rejectsBlankIdentifiers() {
        var repository = mock(PostgresCatalogRepository.class);
        var service = new PostgresMetadataService(repository, new MetadataProperties(), mock(PostgresTypeService.class));

        assertThatThrownBy(() -> service.describeTable(" ", "users"))
            .isInstanceOf(MetadataException.class)
            .hasMessage("Invalid schema name");
    }
}

