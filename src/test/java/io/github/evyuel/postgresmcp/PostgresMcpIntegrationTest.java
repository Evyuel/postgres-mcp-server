package io.github.evyuel.postgresmcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evyuel.postgresmcp.mcp.PostgresMetadataTools;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresMcpIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.read-only", () -> false);
        registry.add("app.metadata.allowed-schemas", () -> "app");
    }

    @BeforeAll
    static void createSchema(@Autowired DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("postgres-test-schema.sql")).execute(dataSource);
    }

    @Autowired PostgresMetadataTools tools;
    @Test
    void allSevenToolsReadRealPostgresCatalogs() {
        assertThat(tools.listSchemas().items()).extracting("schemaName").containsExactly("app");
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

}
