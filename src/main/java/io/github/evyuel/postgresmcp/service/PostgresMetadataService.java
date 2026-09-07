package io.github.evyuel.postgresmcp.service;

import static io.github.evyuel.postgresmcp.dto.MetadataModels.*;

import io.github.evyuel.postgresmcp.config.MetadataProperties;
import io.github.evyuel.postgresmcp.exception.MetadataException;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository.ConstraintRow;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository.RelationRef;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class PostgresMetadataService {
    private final PostgresCatalogRepository repository;
    private final MetadataProperties properties;

    public PostgresMetadataService(PostgresCatalogRepository repository, MetadataProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public LimitedResult<SchemaInfo> listSchemas() {
        return safely(() -> limited(repository.listSchemas(properties.getAllowedSchemas(), fetchLimit())));
    }

    public LimitedResult<TableInfo> listTables(String schema) {
        validateSchema(schema);
        return safely(() -> limited(repository.listTables(schema, fetchLimit())));
    }

    public TableDescription describeTable(String schema, String table) {
        RelationRef relation = relation(schema, table);
        return safely(() -> {
            var constraints = repository.listConstraints(relation.oid());
            ConstraintInfo primaryKey = constraints.stream().filter(c -> c.type().equals("p"))
                .findFirst().map(PostgresMetadataService::toConstraint).orElse(null);
            var uniques = constraints.stream().filter(c -> c.type().equals("u"))
                .map(PostgresMetadataService::toConstraint).toList();
            var checks = constraints.stream().filter(c -> c.type().equals("c"))
                .map(PostgresMetadataService::toConstraint).toList();
            return new TableDescription(relation.schema(), relation.name(), relation.objectType(), relation.comment(),
                repository.listColumns(relation.oid()), primaryKey, uniques, checks,
                repository.partitioning(relation.oid()));
        });
    }

    public DdlResult getTableDdl(String schema, String table) {
        RelationRef relation = relation(schema, table);
        return safely(() -> buildDdl(relation));
    }

    public LimitedResult<IndexInfo> listIndexes(String schema, String table) {
        RelationRef relation = relation(schema, table);
        return safely(() -> limited(repository.listIndexes(relation.oid(), fetchLimit())));
    }

    public LimitedResult<ForeignKeyInfo> listForeignKeys(String schema, String table) {
        RelationRef relation = relation(schema, table);
        return safely(() -> limited(repository.listForeignKeys(relation.oid(), fetchLimit())));
    }

    public LimitedResult<FunctionInfo> listFunctions(String schema) {
        validateSchema(schema);
        return safely(() -> limited(repository.listFunctions(schema, fetchLimit())));
    }

    private DdlResult buildDdl(RelationRef relation) {
        String qualifiedName = quote(relation.schema()) + "." + quote(relation.name());
        List<String> limitations = new ArrayList<>();
        String create;
        if (relation.relkind().equals("v") || relation.relkind().equals("m")) {
            String prefix = relation.relkind().equals("m") ? "CREATE MATERIALIZED VIEW " : "CREATE VIEW ";
            create = prefix + qualifiedName + " AS\n" + repository.viewDefinition(relation.oid()).strip() + ";";
            limitations.add("Ownership, grants, comments, storage parameters and view options are not included.");
        } else {
            var columns = repository.listColumns(relation.oid());
            var constraints = repository.listConstraints(relation.oid());
            var partitioning = repository.partitioning(relation.oid());
            var definitions = new ArrayList<String>();
            for (ColumnInfo column : columns) definitions.add(columnDdl(column));
            for (ConstraintRow constraint : constraints) {
                definitions.add("CONSTRAINT " + quote(constraint.name()) + " " + constraint.definition());
            }
            String keyword = relation.relkind().equals("f") ? "CREATE FOREIGN TABLE " : "CREATE TABLE ";
            create = keyword + qualifiedName + " (\n  " + String.join(",\n  ", definitions) + "\n)";
            if (partitioning != null && partitioning.strategy() != null) {
                create += "\nPARTITION BY " + partitioning.keyDefinition();
            }
            if (partitioning != null && partitioning.partition()) {
                limitations.add("Partition parent attachment is reported in partitioning metadata; generated CREATE TABLE is a structural approximation.");
            }
            if (relation.relkind().equals("f")) {
                create += "\n/* SERVER and OPTIONS must be restored separately */";
                limitations.add("Foreign server and FDW options are not reconstructed.");
            }
            create += ";";
            limitations.add("This is catalog-derived explanatory DDL, not a byte-equivalent replacement for pg_dump.");
            limitations.add("Ownership, grants, comments, triggers, rules, storage parameters and tablespace are not included.");
        }
        var indexDdl = repository.listIndexes(relation.oid(), properties.getMaxObjects()).stream()
            .filter(index -> !index.primary()).map(IndexInfo::indexDefinition).toList();
        return new DdlResult(relation.schema(), relation.name(), relation.objectType(), create, indexDdl, limitations);
    }

    private static String columnDdl(ColumnInfo column) {
        StringBuilder ddl = new StringBuilder(quote(column.name())).append(' ').append(column.dataType());
        if (column.collation() != null) ddl.append(" COLLATE ").append(quote(column.collation()));
        if (column.generated() != null) {
            ddl.append(" GENERATED ALWAYS AS (").append(column.defaultExpression()).append(") ")
                .append(column.generated());
        } else if (column.identity() != null) {
            ddl.append(" GENERATED ").append(column.identity().replace('_', ' ')).append(" AS IDENTITY");
        } else if (column.defaultExpression() != null) {
            ddl.append(" DEFAULT ").append(column.defaultExpression());
        }
        if (!column.nullable()) ddl.append(" NOT NULL");
        return ddl.toString();
    }

    private RelationRef relation(String schema, String table) {
        validateSchema(schema);
        requireName(table, "table");
        return safely(() -> {
            RelationRef relation = repository.findRelation(schema, table);
            if (relation == null) throw new MetadataException("Table or view is not visible or does not exist");
            return relation;
        });
    }

    private void validateSchema(String schema) {
        requireName(schema, "schema");
        if (!properties.getAllowedSchemas().isEmpty() && !properties.getAllowedSchemas().contains(schema)) {
            throw new MetadataException("Schema is not in the configured allowlist");
        }
        boolean exists = safely(() -> repository.schemaExists(schema));
        if (!exists) throw new MetadataException("Schema is not visible or does not exist");
    }

    private static void requireName(String value, String parameter) {
        if (value == null || value.isBlank() || value.length() > 63 || value.indexOf('\0') >= 0) {
            throw new MetadataException("Invalid " + parameter + " name");
        }
    }

    private int fetchLimit() { return properties.getMaxObjects() + 1; }

    private <T> LimitedResult<T> limited(List<T> values) {
        boolean truncated = values.size() > properties.getMaxObjects();
        List<T> items = truncated ? List.copyOf(values.subList(0, properties.getMaxObjects())) : List.copyOf(values);
        return new LimitedResult<>(items, truncated, properties.getMaxObjects());
    }

    private static ConstraintInfo toConstraint(ConstraintRow row) {
        return new ConstraintInfo(row.name(), row.columns(), row.definition());
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private <T> T safely(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (MetadataException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new MetadataException("PostgreSQL metadata query failed", exception);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get(); }
}

