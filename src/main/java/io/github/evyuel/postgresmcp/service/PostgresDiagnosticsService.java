package io.github.evyuel.postgresmcp.service;

import static io.github.evyuel.postgresmcp.dto.DiagnosticsModels.*;

import tools.jackson.databind.ObjectMapper;
import io.github.evyuel.postgresmcp.config.DiagnosticsProperties;
import io.github.evyuel.postgresmcp.exception.DiagnosticException;
import io.github.evyuel.postgresmcp.repository.PostgresCatalogRepository;
import io.github.evyuel.postgresmcp.repository.PostgresDiagnosticsRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class PostgresDiagnosticsService {
    private final PostgresDiagnosticsRepository repository;
    private final PostgresCatalogRepository catalog;
    private final DiagnosticsProperties properties;
    private final ObjectMapper objectMapper;

    public PostgresDiagnosticsService(PostgresDiagnosticsRepository repository,
            PostgresCatalogRepository catalog, DiagnosticsProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.catalog = catalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ToolResponse<StatisticsResult> tableStatistics(String schema, String table, Boolean includePartitions) {
        return statistics(schema, table, properties.getMaxLimit(), capabilities ->
            repository.tableStatistics(schema, table, includePartitions == null || includePartitions,
                properties.getMaxLimit() + 1), List.of());
    }

    public ToolResponse<StatisticsResult> columnStatistics(String schema, String table, String column) {
        if (column != null) requireName(column, "column");
        return statistics(schema, table, properties.getMaxLimit(), capabilities ->
            repository.columnStatistics(schema, table, blankToNull(column), properties.getMaxLimit() + 1),
            List.of("Statistics may be absent or stale until PostgreSQL ANALYZE has processed the table."));
    }

    public ToolResponse<StatisticsResult> indexStatistics(String schema, String table) {
        return statistics(schema, table, properties.getMaxLimit(), capabilities ->
            repository.indexStatistics(schema, table, properties.getMaxLimit() + 1),
            List.of("A zero scan count since statistics reset is not sufficient evidence that an index can be dropped."));
    }

    public ToolResponse<StatisticsResult> extendedStatistics(String schema, String table) {
        return statistics(schema, table, properties.getMaxLimit(), capabilities ->
            repository.extendedStatistics(schema, table, properties.getMaxLimit() + 1),
            List.of("Most-common-value contents are deliberately omitted; only their availability is reported."));
    }

    public ToolResponse<StatisticsResult> queryStatistics(Long queryId, Long databaseId, Long userId,
            Long minCalls, Double minTotalExecutionMs, Integer limit) {
        try {
            int actualLimit = limit(limit);
            if (minCalls != null && minCalls < 0) return invalid("minCalls must not be negative");
            if (minTotalExecutionMs != null && minTotalExecutionMs < 0) return invalid("minTotalExecutionMs must not be negative");
            CapabilityInfo capabilities = supportedCapabilities();
            if (!capabilities.pgStatStatements()) {
                return ToolResponse.failure(Status.FEATURE_UNAVAILABLE, "pg_stat_statements is not installed");
            }
            List<Map<String, Object>> rows = repository.queryStatistics(queryId, databaseId, userId,
                minCalls, minTotalExecutionMs, actualLimit + 1);
            return bounded(capabilities, rows, actualLimit,
                List.of("SQL text and parameter values are deliberately omitted."));
        } catch (IllegalArgumentException exception) { return invalid(exception.getMessage()); }
        catch (DiagnosticException exception) { return ToolResponse.failure(exception.getStatus(), exception.getMessage()); }
        catch (DataAccessException exception) { return databaseFailure(exception); }
    }

    public ToolResponse<StatisticsResult> activeQueries(Long minDurationMs, Boolean includeIdle,
                                                         Boolean includeOwn, Integer limit) {
        try {
            int actualLimit = limit(limit);
            long duration = minDurationMs == null ? 1_000L : minDurationMs;
            if (duration < 0) return invalid("minDurationMs must not be negative");
            CapabilityInfo capabilities = supportedCapabilities();
            List<Map<String, Object>> rows = repository.activeQueries(duration,
                includeIdle == null || includeIdle, Boolean.TRUE.equals(includeOwn), actualLimit + 1);
            return bounded(capabilities, rows, actualLimit, List.of(
                "SQL text, parameters, client address and application name are deliberately omitted.",
                "Rows belonging to other roles can be incomplete without PostgreSQL statistics privileges."));
        } catch (IllegalArgumentException exception) { return invalid(exception.getMessage()); }
        catch (DiagnosticException exception) { return ToolResponse.failure(exception.getStatus(), exception.getMessage()); }
        catch (DataAccessException exception) { return databaseFailure(exception); }
    }

    private ToolResponse<StatisticsResult> statistics(String schema, String table, int limit,
            RowsSupplier supplier, List<String> warnings) {
        try {
            relation(schema, table);
            CapabilityInfo capabilities = supportedCapabilities();
            return bounded(capabilities, supplier.get(capabilities), limit, warnings);
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        } catch (DiagnosticException exception) {
            return ToolResponse.failure(exception.getStatus(), exception.getMessage());
        } catch (DataAccessException exception) { return databaseFailure(exception); }
    }

    private CapabilityInfo supportedCapabilities() {
        CapabilityInfo capabilities = repository.capabilities();
        if (!capabilities.postgres14()) {
            throw new DiagnosticException(Status.FEATURE_UNAVAILABLE, "Only PostgreSQL 14.x is supported");
        }
        return capabilities;
    }

    private ToolResponse<StatisticsResult> bounded(CapabilityInfo capabilities,
            List<Map<String, Object>> rows, int limit, List<String> baseWarnings) {
        boolean truncated = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
        List<String> warnings = new ArrayList<>(capabilities.warnings());
        warnings.addAll(baseWarnings);
        if (truncated) warnings.add("Result was truncated at the configured item limit.");
        while (true) {
            var response = ToolResponse.success(new StatisticsResult(capabilities, List.copyOf(items)), warnings, truncated);
            try {
                if (objectMapper.writeValueAsBytes(response).length <= properties.getMaxResponseBytes()) return response;
            } catch (Exception exception) {
                return ToolResponse.failure(Status.DATABASE_ERROR, "Could not serialize diagnostics response");
            }
            if (items.isEmpty()) return ToolResponse.failure(Status.RESULT_TOO_LARGE, "diagnostics response exceeds 20 MiB");
            items.remove(items.size() - 1);
            truncated = true;
            if (!warnings.contains("Result was truncated to satisfy the 20 MiB response limit.")) {
                warnings.add("Result was truncated to satisfy the 20 MiB response limit.");
            }
        }
    }

    private void relation(String schema, String table) {
        requireName(schema, "schema"); requireName(table, "table");
        if (!catalog.schemaExists(schema) || catalog.findRelation(schema, table) == null) {
            throw new IllegalArgumentException("table is not visible or does not exist");
        }
    }

    private int limit(Integer requested) {
        int value = requested == null ? properties.getDefaultLimit() : requested;
        if (value < 1 || value > properties.getMaxLimit()) throw new IllegalArgumentException("limit must be between 1 and 500");
        return value;
    }

    private static void requireName(String value, String parameter) {
        if (value == null || value.isBlank() || value.length() > 63 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + parameter + " name");
        }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static <T> ToolResponse<T> invalid(String message) { return ToolResponse.failure(Status.INVALID_ARGUMENT, message); }

    private ToolResponse<StatisticsResult> databaseFailure(DataAccessException exception) {
        if (exception.contains(SQLException.class)) {
            Throwable cause = exception.getMostSpecificCause();
            if (cause instanceof SQLException sql && "42501".equals(sql.getSQLState())) {
                return ToolResponse.failure(Status.INSUFFICIENT_PRIVILEGES, "database role lacks a required statistics privilege");
            }
        }
        return ToolResponse.failure(Status.DATABASE_ERROR, "PostgreSQL statistics query failed");
    }

    @FunctionalInterface
    private interface RowsSupplier { List<Map<String, Object>> get(CapabilityInfo capabilities); }
}
