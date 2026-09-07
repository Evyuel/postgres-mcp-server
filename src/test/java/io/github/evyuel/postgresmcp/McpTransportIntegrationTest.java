package io.github.evyuel.postgresmcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.evyuel.postgresmcp.dto.MetadataModels.ColumnInfo;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository.RelationRef;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.metadata.allowed-schemas=app",
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-used"
})
class McpTransportIntegrationTest {
    @MockitoBean PostgresCatalogRepository repository;
    @LocalServerPort int port;

    @BeforeEach
    void catalogResponse() {
        when(repository.schemaExists("app")).thenReturn(true);
        when(repository.findRelation("app", "customer"))
            .thenReturn(new RelationRef(42L, "app", "customer", "TABLE", "r", false, "Customers"));
        when(repository.listColumns(42L)).thenReturn(List.of(
            new ColumnInfo("id", 1, "bigint", false, null, "ALWAYS", null, null, null, true)));
        when(repository.listConstraints(42L)).thenReturn(List.of());
        when(repository.partitioning(42L)).thenReturn(null);
    }

    @Test
    void officialClientListsAndCallsToolsOverStreamableHttp() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint("/mcp").build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "list_schemas", "list_tables", "describe_table", "get_table_ddl",
                "list_indexes", "list_foreign_keys", "list_functions");

            var result = client.callTool(McpSchema.CallToolRequest.builder("describe_table")
                .arguments(Map.of("schema", "app", "table", "customer")).build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.content()).isNotEmpty();
        }
    }
}

