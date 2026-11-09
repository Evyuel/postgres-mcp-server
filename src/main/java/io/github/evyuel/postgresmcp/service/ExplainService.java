package io.github.evyuel.postgresmcp.service;

import static io.github.evyuel.postgresmcp.dto.DiagnosticsModels.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.evyuel.postgresmcp.config.DiagnosticsProperties;
import io.github.evyuel.postgresmcp.exception.DiagnosticException;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ExplainService {
    private final DataSource dataSource;
    private final DiagnosticsProperties properties;
    private final ObjectMapper objectMapper;
    private final Semaphore permits;
    private final ExecutorService executor;

    public ExplainService(@Qualifier("diagnosticsDataSource") DataSource dataSource,
                          DiagnosticsProperties properties, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.permits = new Semaphore(properties.getMaxConcurrent());
        this.executor = Executors.newFixedThreadPool(properties.getMaxConcurrent(), runnable -> {
            Thread thread = new Thread(runnable, "postgres-explain-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ToolResponse<ExplainResult> explain(String sql, Boolean analyze, Boolean buffers,
            Boolean verbose, Boolean settings, Boolean wal, Boolean timing, Boolean summary,
            String format, Integer timeoutSeconds) {
        if (!permits.tryAcquire()) {
            return ToolResponse.failure(Status.CAPACITY_EXCEEDED, "two diagnostics are already running");
        }
        RunningQuery running = null;
        Future<ExplainResult> future = null;
        try {
            String validated = SqlSafetyValidator.validate(sql, properties.getMaxSqlBytes());
            Options options = options(analyze, buffers, verbose, settings, wal, timing, summary, format, timeoutSeconds);
            running = new RunningQuery(validated, options);
            future = executor.submit(running::execute);
            ExplainResult result = future.get(options.timeoutSeconds(), TimeUnit.SECONDS);
            ToolResponse<ExplainResult> response = ToolResponse.success(result);
            if (objectMapper.writeValueAsBytes(response).length > properties.getMaxResponseBytes()) {
                return ToolResponse.failure(Status.RESULT_TOO_LARGE, "diagnostics response exceeds 20 MiB");
            }
            return response;
        } catch (DiagnosticException exception) {
            return ToolResponse.failure(exception.getStatus(), exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (running != null) running.cancel();
            if (future != null) future.cancel(true);
            return ToolResponse.failure(Status.CANCELLED, "EXPLAIN was cancelled");
        } catch (TimeoutException exception) {
            if (running != null) running.cancel();
            if (future != null) future.cancel(true);
            return ToolResponse.failure(Status.TIMEOUT, "EXPLAIN exceeded its end-to-end timeout");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof DiagnosticException diagnostic) {
                return ToolResponse.failure(diagnostic.getStatus(), diagnostic.getMessage());
            }
            return ToolResponse.failure(Status.DATABASE_ERROR, "PostgreSQL could not produce the plan");
        } catch (Exception exception) {
            return ToolResponse.failure(Status.DATABASE_ERROR, "PostgreSQL could not produce the plan");
        } finally {
            permits.release();
        }
    }

    private Options options(Boolean analyze, Boolean buffers, Boolean verbose, Boolean settings,
            Boolean wal, Boolean timing, Boolean summary, String format, Integer timeoutSeconds) {
        boolean a = Boolean.TRUE.equals(analyze);
        boolean b = Boolean.TRUE.equals(buffers);
        boolean v = verbose == null || verbose;
        boolean s = settings == null || settings;
        boolean w = Boolean.TRUE.equals(wal);
        boolean t = Boolean.TRUE.equals(timing);
        boolean sum = summary == null || summary;
        int timeout = timeoutSeconds == null ? properties.getMaxTimeoutSeconds() : timeoutSeconds;
        if (timeout < 1 || timeout > properties.getMaxTimeoutSeconds()) {
            throw new DiagnosticException(Status.INVALID_ARGUMENT, "timeoutSeconds must be between 1 and 300");
        }
        if (format != null && !format.equalsIgnoreCase("JSON")) {
            throw new DiagnosticException(Status.INVALID_EXPLAIN_OPTIONS, "only JSON format is supported");
        }
        if ((b || w || t) && !a) {
            throw new DiagnosticException(Status.INVALID_EXPLAIN_OPTIONS, "BUFFERS, WAL and TIMING require analyze=true");
        }
        return new Options(a, b, v, s, w, t, sum, timeout);
    }

    private final class RunningQuery {
        private final String sql;
        private final Options options;
        private volatile Statement currentStatement;
        private volatile boolean cancelled;

        private RunningQuery(String sql, Options options) { this.sql = sql; this.options = options; }

        private ExplainResult execute() {
            try (Connection connection = dataSource.getConnection()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try {
                    configureTransaction(connection);
                    JsonNode preflight = executeExplain(connection,
                        "EXPLAIN (ANALYZE FALSE, VERBOSE FALSE, FORMAT JSON) " + sql, options.timeoutSeconds());
                    if (containsModifyingPlan(preflight)) {
                        throw new DiagnosticException(Status.NON_SELECT_STATEMENT, "data-modifying statements are not allowed");
                    }
                    String explain = "EXPLAIN (" + optionSql(options) + ") " + sql;
                    JsonNode plan = executeExplain(connection, explain, options.timeoutSeconds());
                    int bytes = plan.toString().getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > properties.getMaxPlanBytes()) {
                        throw new DiagnosticException(Status.RESULT_TOO_LARGE, "EXPLAIN JSON exceeds 16 MiB");
                    }
                    Map<String, Boolean> flags = new LinkedHashMap<>();
                    flags.put("analyze", options.analyze()); flags.put("buffers", options.buffers());
                    flags.put("verbose", options.verbose()); flags.put("settings", options.settings());
                    flags.put("wal", options.wal()); flags.put("timing", options.timing());
                    flags.put("summary", options.summary());
                    return new ExplainResult(plan, options.analyze(), options.timeoutSeconds(), flags, bytes);
                } finally {
                    currentStatement = null;
                    try { connection.rollback(); } catch (SQLException ignored) { }
                }
            } catch (SQLException exception) {
                throw map(exception);
            }
        }

        private void configureTransaction(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                currentStatement = statement;
                try (var rs = statement.executeQuery("SHOW server_version_num")) {
                    rs.next();
                    int version = rs.getInt(1);
                    if (version < 140000 || version >= 150000) {
                        throw new DiagnosticException(Status.FEATURE_UNAVAILABLE, "Only PostgreSQL 14.x is supported");
                    }
                }
                statement.execute("SET LOCAL statement_timeout = '" + (options.timeoutSeconds() * 1000L) + "ms'");
                statement.execute("SET LOCAL lock_timeout = '" + (properties.getLockTimeoutSeconds() * 1000L) + "ms'");
                statement.execute("SET LOCAL transaction_read_only = on");
            } finally { currentStatement = null; }
        }

        private JsonNode executeExplain(Connection connection, String query, int timeout) throws SQLException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new DiagnosticException(Status.CANCELLED, "EXPLAIN was cancelled");
            }
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                currentStatement = statement;
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    throw new DiagnosticException(Status.CANCELLED, "EXPLAIN was cancelled");
                }
                statement.setQueryTimeout(timeout + 1);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) throw new SQLException("EXPLAIN returned no JSON");
                    String json = rs.getString(1);
                    if (json.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPlanBytes()) {
                        throw new DiagnosticException(Status.RESULT_TOO_LARGE, "EXPLAIN JSON exceeds 16 MiB");
                    }
                    return objectMapper.readTree(json);
                }
            } finally { currentStatement = null; }
        }

        private void cancel() {
            cancelled = true;
            Statement statement = currentStatement;
            if (statement != null) try { statement.cancel(); } catch (SQLException ignored) { }
        }
    }

    private static String optionSql(Options o) {
        StringBuilder sql = new StringBuilder("ANALYZE ").append(o.analyze())
            .append(", VERBOSE ").append(o.verbose())
            .append(", SETTINGS ").append(o.settings())
            .append(", SUMMARY ").append(o.summary());
        if (o.analyze()) {
            sql.append(", BUFFERS ").append(o.buffers())
                .append(", WAL ").append(o.wal())
                .append(", TIMING ").append(o.timing());
        }
        return sql.append(", FORMAT JSON").toString();
    }

    private static boolean containsModifyingPlan(JsonNode node) {
        if (node.isObject() && node.has("Node Type") && "ModifyTable".equals(node.get("Node Type").asText())) return true;
        if (node.isObject() && node.has("Operation")) {
            String operation = node.get("Operation").asText();
            if (operation.equals("Insert") || operation.equals("Update") || operation.equals("Delete") || operation.equals("Merge")) return true;
        }
        for (JsonNode child : node) if (containsModifyingPlan(child)) return true;
        return false;
    }

    private static DiagnosticException map(SQLException exception) {
        String state = exception.getSQLState();
        if ("57014".equals(state)) return new DiagnosticException(Status.TIMEOUT, "PostgreSQL cancelled the query after its timeout", exception);
        if ("42501".equals(state)) return new DiagnosticException(Status.INSUFFICIENT_PRIVILEGES, "database role lacks a required privilege", exception);
        if ("25006".equals(state)) return new DiagnosticException(Status.NON_SELECT_STATEMENT, "statement is not allowed in a read-only transaction", exception);
        if ("0A000".equals(state)) return new DiagnosticException(Status.FEATURE_UNAVAILABLE, "requested PostgreSQL feature is unavailable", exception);
        if (state != null && (state.startsWith("42") || state.startsWith("22"))) {
            return new DiagnosticException(Status.INVALID_ARGUMENT, "PostgreSQL rejected the SELECT statement", exception);
        }
        return new DiagnosticException(Status.DATABASE_ERROR, "PostgreSQL could not produce the plan", exception);
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }

    private record Options(boolean analyze, boolean buffers, boolean verbose, boolean settings,
                           boolean wal, boolean timing, boolean summary, int timeoutSeconds) {}
}
