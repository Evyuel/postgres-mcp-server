package io.github.evyuel.postgresmcp.dto;

import java.util.List;

public final class MetadataModels {
    private MetadataModels() {}

    public record LimitedResult<T>(List<T> items, boolean truncated, int limit) {}
    public record SchemaInfo(String schemaName, String owner, String comment, boolean systemSchema) {}
    public record TableInfo(String schema, String name, String objectType, String owner, String comment,
                            Long estimatedRowCount, boolean approximateRowCount, boolean partitioned,
                            boolean partition) {}
    public enum DdlCompleteness { STRUCTURAL_COMPLETE, INFORMATIONAL, NOT_APPLICABLE }
    public record TypeAttribute(String name, String dataType, boolean nullable, TypeDetails typeDetails) {}
    public record TypeDetails(Long oid, String schema, String name, String formattedName, String kind,
                              DdlCompleteness ddlCompleteness, String reconstructedDdl,
                              List<String> enumValues, String baseType, String defaultExpression,
                              Boolean notNull, TypeDetails baseTypeDetails,
                              List<String> constraints, List<TypeAttribute> attributes,
                              String rangeSubtype, String multirangeType, String rangeOperatorClass,
                              String rangeCanonicalFunction, String rangeSubtypeDiffFunction,
                              TypeDetails elementType,
                              List<String> limitations) {}
    public record TypeDependency(String schema, String name, String kind,
                                 DdlCompleteness ddlCompleteness, String definition) {}
    public record ColumnInfo(String name, int ordinalPosition, String dataType, boolean nullable,
                             String defaultExpression, String identity, String generated,
                             String comment, String collation, boolean primaryKey, TypeDetails typeDetails) {
        public ColumnInfo(String name, int ordinalPosition, String dataType, boolean nullable,
                          String defaultExpression, String identity, String generated,
                          String comment, String collation, boolean primaryKey) {
            this(name, ordinalPosition, dataType, nullable, defaultExpression, identity, generated,
                comment, collation, primaryKey, null);
        }
    }
    public record ConstraintInfo(String name, List<String> columns, String definition) {}
    public record PartitioningInfo(String strategy, String keyDefinition, boolean partition,
                                   String parentSchema, String parentTable, String partitionBound) {}
    public record TableDescription(String schema, String name, String objectType, String comment,
                                   List<ColumnInfo> columns, ConstraintInfo primaryKey,
                                   List<ConstraintInfo> uniqueConstraints,
                                   List<ConstraintInfo> checkConstraints,
                                   PartitioningInfo partitioning) {}
    public record DdlResult(String schema, String name, String objectType, String createStatement,
                            List<String> indexes, List<String> limitations,
                            List<TypeDependency> typeDependencies) {
        public DdlResult(String schema, String name, String objectType, String createStatement,
                         List<String> indexes, List<String> limitations) {
            this(schema, name, objectType, createStatement, indexes, limitations, List.of());
        }
    }
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

