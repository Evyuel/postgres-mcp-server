package io.github.evyuel.postgresmcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.Status;
import io.github.evyuel.postgresmcp.dto.MetadataModels.DdlCompleteness;
import io.github.evyuel.postgresmcp.mcp.PostgresMetadataTools;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresMcpIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:14.22-alpine"))
        .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "compute_query_id=on");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.read-only", () -> false);
    }

    @BeforeAll
    static void createSchema(@Autowired DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("postgres-test-schema.sql")).execute(dataSource);
    }

    @Autowired PostgresMetadataTools tools;
    @LocalServerPort int port;
    @Test
    void allSevenToolsReadRealPostgresCatalogs() {
        assertThat(tools.listSchemas().items()).extracting("schemaName").contains("app");
        assertThat(tools.listTables("app").items()).extracting("name")
            .contains("customer", "purchase", "customer_view", "events", "events_2026");

        var description = tools.describeTable("app", "purchase");
        assertThat(description.primaryKey().columns()).containsExactly("customer_id", "order_no");
        assertThat(description.checkConstraints()).isNotEmpty();

        var ddl = tools.getTableDdl("app", "customer");
        assertThat(ddl.createStatement()).contains("CREATE TABLE \"app\".\"customer\"")
            .contains("numeric(12,2)").contains("AS IDENTITY");

        assertThat(tools.listIndexes("app", "purchase").items())
            .anySatisfy(index -> assertThat(index.predicate()).contains("status"))
            .anySatisfy(index -> assertThat(index.columnsOrExpressions()).anyMatch(s -> s.contains("lower")));

        assertThat(tools.listForeignKeys("app", "purchase").items()).singleElement().satisfies(fk -> {
            assertThat(fk.sourceColumns()).containsExactly("customer_id");
            assertThat(fk.targetColumns()).containsExactly("id");
            assertThat(fk.onUpdate()).isEqualTo("CASCADE");
            assertThat(fk.onDelete()).isEqualTo("RESTRICT");
        });

        assertThat(tools.listFunctions("app").items())
            .filteredOn(function -> function.name().equals("lookup_customer")).hasSize(2);
        assertThat(tools.listFunctions("app").items())
            .anySatisfy(function -> assertThat(function.kind()).isEqualTo("PROCEDURE"));
    }

    @Test
    void customTypesExposeStructuralAndInformationalDefinitions() {
        var enumType = tools.describeType("app", "order_status");
        assertThat(enumType.status()).isEqualTo(Status.SUCCESS);
        assertThat(enumType.data().enumValues()).containsExactly("NEW", "PAID", "CANCELLED");
        assertThat(enumType.data().ddlCompleteness()).isEqualTo(DdlCompleteness.STRUCTURAL_COMPLETE);
        assertThat(enumType.data().reconstructedDdl()).contains("CREATE TYPE", "AS ENUM");

        var domain = tools.describeType("app", "positive_amount");
        assertThat(domain.data().ddlCompleteness()).isEqualTo(DdlCompleteness.STRUCTURAL_COMPLETE);
        assertThat(domain.data().reconstructedDdl()).contains("CREATE DOMAIN", "CHECK");

        assertThat(tools.describeType("app", "address").data().ddlCompleteness())
            .isEqualTo(DdlCompleteness.INFORMATIONAL);
        assertThat(tools.describeType("app", "price_range").data().ddlCompleteness())
            .isEqualTo(DdlCompleteness.INFORMATIONAL);

        var table = tools.describeTable("app", "typed_sample");
        assertThat(table.columns()).filteredOn(column -> column.name().equals("status"))
            .singleElement().satisfies(column -> assertThat(column.typeDetails().kind()).isEqualTo("ENUM"));
        assertThat(tools.getTableDdl("app", "typed_sample").typeDependencies())
            .anySatisfy(type -> assertThat(type.name()).isEqualTo("order_status"));
    }

    @Test
    void diagnosticsReturnMetricsWithoutBusinessValues() {
        assertThat(tools.getTableStatistics("app", "events", true).status()).isEqualTo(Status.SUCCESS);

        var columns = tools.getColumnStatistics("app", "purchase", null);
        assertThat(columns.status()).isEqualTo(Status.SUCCESS);
        assertThat(columns.data().items()).allSatisfy(item -> {
            assertThat(item).doesNotContainKeys("mostCommonValues", "histogramBounds");
            assertThat(item.keySet()).noneMatch(key -> key.toLowerCase().contains("value"));
        });

        assertThat(tools.getIndexStatistics("app", "purchase").status()).isEqualTo(Status.SUCCESS);
        var extended = tools.getExtendedStatistics("app", "purchase");
        assertThat(extended.status()).isEqualTo(Status.SUCCESS);
        assertThat(extended.data().items()).allSatisfy(item ->
            assertThat(item).doesNotContainKeys("most_common_vals", "mostCommonValues"));

        var queries = tools.getQueryStatistics(null, null, null, 0L, 0D, 10);
        assertThat(queries.status()).isEqualTo(Status.SUCCESS);
        assertThat(queries.data().items()).allSatisfy(item -> assertThat(item).doesNotContainKeys("query", "queryText"));

        var active = tools.getActiveQueries(0L, true, true, 10);
        assertThat(active.status()).isEqualTo(Status.SUCCESS);
        assertThat(active.data().items()).allSatisfy(item ->
            assertThat(item).doesNotContainKeys("query", "clientAddress", "applicationName"));
    }

    @Test
    void explainIsSelectOnlyBoundedAndAnalyzeIsOptIn() {
        var plan = tools.explainQuery("SELECT * FROM app.purchase WHERE status = 'PAID'",
            null, null, null, null, null, null, null, null, null);
        assertThat(plan.status()).isEqualTo(Status.SUCCESS);
        assertThat(plan.data().analyzed()).isFalse();
        assertThat(plan.data().options()).containsEntry("verbose", true).containsEntry("settings", true);

        assertThat(tools.explainQuery("UPDATE app.purchase SET total = 1", true, false,
            true, true, false, false, true, "JSON", 10).status()).isEqualTo(Status.NON_SELECT_STATEMENT);
        assertThat(tools.explainQuery("SELECT 1; SELECT 2", false, false,
            true, true, false, false, true, "JSON", 10).status()).isEqualTo(Status.NON_SELECT_STATEMENT);
        assertThat(tools.explainQuery("SELECT pg_sleep(2)", true, false,
            true, true, false, false, true, "JSON", 1).status()).isEqualTo(Status.TIMEOUT);
    }

    @Test
    void thirdConcurrentExplainFailsFast() throws Exception {
        var first = CompletableFuture.supplyAsync(() -> tools.explainQuery("SELECT pg_sleep(1)", true,
            false, true, true, false, false, true, "JSON", 5));
        var second = CompletableFuture.supplyAsync(() -> tools.explainQuery("SELECT pg_sleep(1)", true,
            false, true, true, false, false, true, "JSON", 5));
        Thread.sleep(250);
        assertThat(tools.explainQuery("SELECT 1", false, false, true, true,
            false, false, true, "JSON", 5).status()).isEqualTo(Status.CAPACITY_EXCEEDED);
        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(Status.SUCCESS);
        assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(Status.SUCCESS);
    }

    @Test
    void allFifteenToolsAreCallableOverStreamableHttp() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint("/mcp").build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).hasSize(15);
            call(client, "list_schemas", Map.of());
            call(client, "list_tables", Map.of("schema", "app"));
            call(client, "describe_table", Map.of("schema", "app", "table", "purchase"));
            call(client, "get_table_ddl", Map.of("schema", "app", "table", "typed_sample"));
            call(client, "list_indexes", Map.of("schema", "app", "table", "purchase"));
            call(client, "list_foreign_keys", Map.of("schema", "app", "table", "purchase"));
            call(client, "list_functions", Map.of("schema", "app"));
            call(client, "describe_type", Map.of("schema", "app", "type", "order_status"));
            call(client, "explain_query", Map.of("sql", "SELECT 1"));
            call(client, "get_table_statistics", Map.of("schema", "app", "table", "purchase"));
            call(client, "get_column_statistics", Map.of("schema", "app", "table", "purchase"));
            call(client, "get_index_statistics", Map.of("schema", "app", "table", "purchase"));
            call(client, "get_extended_statistics", Map.of("schema", "app", "table", "purchase"));
            call(client, "get_query_statistics", Map.of());
            call(client, "get_active_queries", Map.of());
        }
    }

    private static void call(io.modelcontextprotocol.client.McpSyncClient client, String tool,
                             Map<String, Object> arguments) {
        var result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        assertThat(result.isError()).as(tool).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content()).as(tool).isNotEmpty();
    }

}
