package io.github.evyuel.postgresmcp.mcp;

import static io.github.evyuel.postgresmcp.dto.MetadataModels.*;

import io.github.evyuel.postgresmcp.service.PostgresMetadataService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class PostgresMetadataTools {
    private final PostgresMetadataService service;

    public PostgresMetadataTools(PostgresMetadataService service) { this.service = service; }

    @McpTool(name = "list_schemas", description = "List visible non-system PostgreSQL schemas allowed by server policy.")
    public LimitedResult<SchemaInfo> listSchemas() { return service.listSchemas(); }

    @McpTool(name = "list_tables", description = "List tables, views, materialized views and foreign tables in a PostgreSQL schema. Row counts are planner estimates.")
    public LimitedResult<TableInfo> listTables(
            @McpToolParam(description = "Exact PostgreSQL schema name, preserving case", required = true) String schema) {
        return service.listTables(schema);
    }

    @McpTool(name = "describe_table", description = "Describe columns, keys, checks and partitioning for a visible PostgreSQL table or view.")
    public TableDescription describeTable(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table or view name", required = true) String table) {
        return service.describeTable(schema, table);
    }

    @McpTool(name = "get_table_ddl", description = "Build explanatory catalog-derived DDL for a table or view. This is not a replacement for pg_dump.")
    public DdlResult getTableDdl(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table or view name", required = true) String table) {
        return service.getTableDdl(schema, table);
    }

    @McpTool(name = "list_indexes", description = "List PostgreSQL indexes, key expressions, INCLUDE columns, predicates, validity and catalog definitions.")
    public LimitedResult<IndexInfo> listIndexes(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table name", required = true) String table) {
        return service.listIndexes(schema, table);
    }

    @McpTool(name = "list_foreign_keys", description = "List outgoing foreign keys with ordered source and target columns and referential actions.")
    public LimitedResult<ForeignKeyInfo> listForeignKeys(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table name", required = true) String table) {
        return service.listForeignKeys(schema, table);
    }

    @McpTool(name = "list_functions", description = "List visible PostgreSQL functions and procedures with overload-safe signatures, without source code.")
    public LimitedResult<FunctionInfo> listFunctions(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema) {
        return service.listFunctions(schema);
    }
}

