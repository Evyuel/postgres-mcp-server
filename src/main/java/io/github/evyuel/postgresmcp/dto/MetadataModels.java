package io.github.evyuel.postgresmcp.dto;

import java.util.List;

public final class MetadataModels {
    private MetadataModels() {}

    public record LimitedResult<T>(List<T> items, boolean truncated, int limit) {}
    public record SchemaInfo(String schemaName, String owner, String comment, boolean systemSchema) {}
    public record TableInfo(String schema, String name, String objectType, String owner, String comment,
                            Long estimatedRowCount, boolean approximateRowCount, boolean partitioned,
                            boolean partition) {}
    public record ColumnInfo(String name, int ordinalPosition, String dataType, boolean nullable,
                             String defaultExpression, String identity, String generated,
                             String comment, String collation, boolean primaryKey) {}
    public record ConstraintInfo(String name, List<String> columns, String definition) {}
    public record PartitioningInfo(String strategy, String keyDefinition, boolean partition,
                                   String parentSchema, String parentTable, String partitionBound) {}
    public record TableDescription(String schema, String name, String objectType, String comment,
                                   List<ColumnInfo> columns, ConstraintInfo primaryKey,
                                   List<ConstraintInfo> uniqueConstraints,
                                   List<ConstraintInfo> checkConstraints,
                                   PartitioningInfo partitioning) {}
    public record DdlResult(String schema, String name, String objectType, String createStatement,
                            List<String> indexes, List<String> limitations) {}
    public record IndexInfo(String indexName, String accessMethod, boolean unique, boolean primary,
                            boolean valid, boolean ready, List<String> columnsOrExpressions,
                            List<String> includeColumns, String predicate, String indexDefinition,
                            Long indexSizeBytes, String comment) {}
    public record ForeignKeyInfo(String constraintName, String sourceSchema, String sourceTable,
                                 List<String> sourceColumns, String targetSchema, String targetTable,
                                 List<String> targetColumns, String onUpdate, String onDelete,
                                 String matchType, boolean deferrable, boolean initiallyDeferred,
                                 boolean validated, String constraintDefinition) {}
    public record FunctionInfo(String schema, String name, String kind, String identityArguments,
                               String signature, List<String> argumentTypes, String returnType,
                               String language, String volatility, boolean securityDefiner,
                               String comment) {}
}

