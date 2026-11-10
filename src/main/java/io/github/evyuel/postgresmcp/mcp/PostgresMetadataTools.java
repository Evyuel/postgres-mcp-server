package io.github.evyuel.postgresmcp.mcp;

import static io.github.evyuel.postgresmcp.dto.MetadataModels.*;

import io.github.evyuel.postgresmcp.service.PostgresMetadataService;
import io.github.evyuel.postgresmcp.service.PostgresDiagnosticsService;
import io.github.evyuel.postgresmcp.service.PostgresTypeService;
import io.github.evyuel.postgresmcp.service.ExplainService;
import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.ExplainResult;
import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.StatisticsResult;
import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.ToolResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class PostgresMetadataTools {
    private final PostgresMetadataService service;
    private final PostgresTypeService typeService;
    private final ExplainService explainService;
    private final PostgresDiagnosticsService diagnosticsService;

    public PostgresMetadataTools(PostgresMetadataService service, PostgresTypeService typeService,
            ExplainService explainService, PostgresDiagnosticsService diagnosticsService) {
        this.service = service;
        this.typeService = typeService;
        this.explainService = explainService;
        this.diagnosticsService = diagnosticsService;
    }

    @McpTool(name = "list_schemas", description = "List visible non-system PostgreSQL schemas available to the connected role.")
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

    @McpTool(name = "describe_type", description = "Describe a PostgreSQL type. ENUM and DOMAIN include structural DDL; composite, range and multirange definitions are explicitly informational.")
    public ToolResponse<TypeDetails> describeType(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact pg_type type name", required = true) String type) {
        return typeService.describe(schema, type);
    }

    @McpTool(name = "explain_query", description = "Explain one fully literalized SELECT statement. ANALYZE is opt-in and really executes the SELECT in a read-only transaction.")
    public ToolResponse<ExplainResult> explainQuery(
            @McpToolParam(description = "One SELECT or WITH...SELECT statement; maximum UTF-8 size is 2 MiB", required = true) String sql,
            @McpToolParam(description = "Execute the SELECT and return actual metrics; default false", required = false) Boolean analyze,
            @McpToolParam(description = "Include buffer metrics when ANALYZE is true; default false", required = false) Boolean buffers,
            @McpToolParam(description = "Verbose plan output; default true", required = false) Boolean verbose,
            @McpToolParam(description = "Include changed planner settings; default true", required = false) Boolean settings,
            @McpToolParam(description = "Include WAL metrics when ANALYZE is true; default false", required = false) Boolean wal,
            @McpToolParam(description = "Collect per-node timing when ANALYZE is true; default false", required = false) Boolean timing,
            @McpToolParam(description = "Include summary; default true", required = false) Boolean summary,
            @McpToolParam(description = "Output format; only JSON is supported", required = false) String format,
            @McpToolParam(description = "End-to-end timeout from 1 to 300 seconds; default 300", required = false) Integer timeoutSeconds) {
        return explainService.explain(sql, analyze, buffers, verbose, settings, wal, timing, summary, format, timeoutSeconds);
    }

    @McpTool(name = "get_table_statistics", description = "Return bounded table and partition size, row-estimate, scan, tuple, vacuum and analyze counters without business values.")
    public ToolResponse<StatisticsResult> getTableStatistics(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table name", required = true) String table,
            @McpToolParam(description = "Include descendant partitions; default true", required = false) Boolean includePartitions) {
        return diagnosticsService.tableStatistics(schema, table, includePartitions);
    }

    @McpTool(name = "get_column_statistics", description = "Return null fraction, width, distinct estimate and correlation. MCV and histogram values are never returned.")
    public ToolResponse<StatisticsResult> getColumnStatistics(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table name", required = true) String table,
            @McpToolParam(description = "Optional exact column name; omitted means all columns", required = false) String column) {
        return diagnosticsService.columnStatistics(schema, table, column);
    }

    @McpTool(name = "get_index_statistics", description = "Return bounded index usage, size and state statistics. A zero scan count is not a drop recommendation.")
    public ToolResponse<StatisticsResult> getIndexStatistics(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table name", required = true) String table) {
        return diagnosticsService.indexStatistics(schema, table);
    }

    @McpTool(name = "get_extended_statistics", description = "Return extended-statistics definitions, ndistinct and dependencies without most-common values.")
    public ToolResponse<StatisticsResult> getExtendedStatistics(
            @McpToolParam(description = "Exact PostgreSQL schema name", required = true) String schema,
            @McpToolParam(description = "Exact table name", required = true) String table) {
        return diagnosticsService.extendedStatistics(schema, table);
    }

    @McpTool(name = "get_query_statistics", description = "Return pg_stat_statements metrics without SQL text or parameter values.")
    public ToolResponse<StatisticsResult> getQueryStatistics(
            @McpToolParam(description = "Optional query ID", required = false) Long queryId,
            @McpToolParam(description = "Optional database OID", required = false) Long databaseId,
            @McpToolParam(description = "Optional role OID", required = false) Long userId,
            @McpToolParam(description = "Minimum call count", required = false) Long minCalls,
            @McpToolParam(description = "Minimum total execution time in milliseconds", required = false) Double minTotalExecutionMs,
            @McpToolParam(description = "Result limit, default 50 and maximum 500", required = false) Integer limit) {
        return diagnosticsService.queryStatistics(queryId, databaseId, userId, minCalls, minTotalExecutionMs, limit);
    }

    @McpTool(name = "get_active_queries", description = "Return activity durations, waits and blockers without SQL text, parameters, client address or application name.")
    public ToolResponse<StatisticsResult> getActiveQueries(
            @McpToolParam(description = "Minimum query duration in milliseconds; default 1000", required = false) Long minDurationMs,
            @McpToolParam(description = "Include idle states; default true", required = false) Boolean includeIdle,
            @McpToolParam(description = "Include this tool's own backend; default false", required = false) Boolean includeOwn,
            @McpToolParam(description = "Result limit, default 50 and maximum 500", required = false) Integer limit) {
        return diagnosticsService.activeQueries(minDurationMs, includeIdle, includeOwn, limit);
    }
}

