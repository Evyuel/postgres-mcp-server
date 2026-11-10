package io.github.evyuel.postgresmcp.repository;

import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.CapabilityInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresDiagnosticsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PostgresDiagnosticsRepository(NamedParameterJdbcTemplate metadataJdbcTemplate) {
        this.jdbc = metadataJdbcTemplate;
    }

    public CapabilityInfo capabilities() {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT current_setting('server_version') AS version,
                   current_setting('server_version_num')::integer AS version_num,
                   current_setting('track_counts')::boolean AS track_counts,
                   current_setting('track_activities')::boolean AS track_activities,
                   current_setting('track_io_timing')::boolean AS track_io_timing,
                   current_setting('compute_query_id', true) AS compute_query_id,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'pg_stat_statements') AS pgss
            """, Map.of());
        int version = ((Number) row.get("version_num")).intValue();
        List<String> warnings = new ArrayList<>();
        if (version < 140000 || version >= 150000) warnings.add("Only PostgreSQL 14.x is supported.");
        if (!Boolean.TRUE.equals(row.get("track_counts"))) warnings.add("track_counts is disabled; cumulative statistics are incomplete.");
        if (!Boolean.TRUE.equals(row.get("track_activities"))) warnings.add("track_activities is disabled; activity details are incomplete.");
        return new CapabilityInfo(String.valueOf(row.get("version")), version,
            version >= 140000 && version < 150000, Boolean.TRUE.equals(row.get("pgss")),
            Boolean.TRUE.equals(row.get("track_counts")), Boolean.TRUE.equals(row.get("track_activities")),
            Boolean.TRUE.equals(row.get("track_io_timing")), (String) row.get("compute_query_id"), warnings);
    }

    public List<Map<String, Object>> tableStatistics(String schema, String table,
                                                      boolean includePartitions, int limit) {
        return normalized(jdbc.queryForList("""
            WITH RECURSIVE relations AS (
                SELECT c.oid, n.nspname, c.relname, c.relkind, c.relispartition, 0 AS depth
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = :schema AND c.relname = :table AND c.relkind IN ('r','p','m','f')
                UNION ALL
                SELECT child.oid, cn.nspname, child.relname, child.relkind, child.relispartition, r.depth + 1
                FROM relations r
                JOIN pg_catalog.pg_inherits i ON i.inhparent = r.oid
                JOIN pg_catalog.pg_class child ON child.oid = i.inhrelid
                JOIN pg_catalog.pg_namespace cn ON cn.oid = child.relnamespace
                WHERE :include_partitions
            )
            SELECT r.nspname AS "schema", r.relname AS "table", r.depth AS "partitionDepth",
                   r.relispartition AS "partition", c.reltuples::bigint AS "estimatedRows",
                   pg_table_size(c.oid) AS "tableSizeBytes",
                   pg_indexes_size(c.oid) AS "indexSizeBytes",
                   CASE WHEN c.reltoastrelid = 0 THEN 0 ELSE pg_total_relation_size(c.reltoastrelid) END AS "toastSizeBytes",
                   pg_total_relation_size(c.oid) AS "totalSizeBytes",
                   SUM(pg_table_size(c.oid)) OVER () AS "partitionTreeTableSizeBytes",
                   SUM(pg_indexes_size(c.oid)) OVER () AS "partitionTreeIndexSizeBytes",
                   SUM(pg_total_relation_size(c.oid)) OVER () AS "partitionTreeTotalSizeBytes",
                   SUM(COALESCE(s.n_live_tup, 0)) OVER () AS "partitionTreeLiveTuples",
                   SUM(COALESCE(s.n_dead_tup, 0)) OVER () AS "partitionTreeDeadTuples",
                   s.seq_scan AS "sequentialScans", s.seq_tup_read AS "sequentialTuplesRead",
                   s.idx_scan AS "indexScans", s.idx_tup_fetch AS "indexTuplesFetched",
                   s.n_tup_ins AS "tuplesInserted", s.n_tup_upd AS "tuplesUpdated",
                   s.n_tup_del AS "tuplesDeleted", s.n_live_tup AS "liveTuples",
                   s.n_dead_tup AS "deadTuples", s.n_mod_since_analyze AS "modifiedSinceAnalyze",
                   s.last_vacuum AS "lastVacuum", s.last_autovacuum AS "lastAutovacuum",
                   s.last_analyze AS "lastAnalyze", s.last_autoanalyze AS "lastAutoanalyze",
                   s.vacuum_count AS "vacuumCount", s.autovacuum_count AS "autovacuumCount",
                   s.analyze_count AS "analyzeCount", s.autoanalyze_count AS "autoanalyzeCount",
                   d.stats_reset AS "statisticsResetAt"
            FROM relations r
            JOIN pg_catalog.pg_class c ON c.oid = r.oid
            LEFT JOIN pg_catalog.pg_stat_user_tables s ON s.relid = r.oid
            LEFT JOIN pg_catalog.pg_stat_database d ON d.datid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
            ORDER BY r.depth, r.nspname, r.relname
            LIMIT :limit
            """, new MapSqlParameterSource()
                .addValue("schema", schema).addValue("table", table)
                .addValue("include_partitions", includePartitions).addValue("limit", limit)));
    }

    public List<Map<String, Object>> columnStatistics(String schema, String table,
                                                       String column, int limit) {
        return jdbc.queryForList("""
            SELECT s.schemaname AS "schema", s.tablename AS "table", s.attname AS "column",
                   s.inherited AS "inherited", s.null_frac AS "nullFraction",
                   s.avg_width AS "averageWidth", s.n_distinct AS "nDistinct",
                   CASE WHEN s.n_distinct < 0 THEN 'NEGATIVE_FRACTION_OF_ROWS'
                        ELSE 'ESTIMATED_DISTINCT_COUNT' END AS "nDistinctInterpretation",
                   s.correlation AS "correlation", a.attstattarget AS "statisticsTarget",
                   c.reltuples::bigint AS "tableEstimatedRows"
            FROM pg_catalog.pg_stats s
            JOIN pg_catalog.pg_namespace n ON n.nspname = s.schemaname
            JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid AND c.relname = s.tablename
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attname = s.attname
            WHERE s.schemaname = :schema AND s.tablename = :table
              AND (:column IS NULL OR s.attname = :column)
            ORDER BY s.attname, s.inherited
            LIMIT :limit
            """, new MapSqlParameterSource().addValue("schema", schema).addValue("table", table)
                .addValue("column", column).addValue("limit", limit));
    }

    public List<Map<String, Object>> indexStatistics(String schema, String table, int limit) {
        return jdbc.queryForList("""
            SELECT ns.nspname AS "schema", tbl.relname AS "table", idx.relname AS "index",
                   am.amname AS "accessMethod", i.indisunique AS "unique", i.indisprimary AS "primary",
                   i.indisvalid AS "valid", i.indisready AS "ready", i.indislive AS "live",
                   s.idx_scan AS "scans", s.idx_tup_read AS "tuplesRead", s.idx_tup_fetch AS "tuplesFetched",
                   pg_relation_size(idx.oid) AS "sizeBytes", pg_get_indexdef(idx.oid) AS "definition",
                   d.stats_reset AS "statisticsResetAt"
            FROM pg_catalog.pg_class tbl
            JOIN pg_catalog.pg_namespace ns ON ns.oid = tbl.relnamespace
            JOIN pg_catalog.pg_index i ON i.indrelid = tbl.oid
            JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
            JOIN pg_catalog.pg_am am ON am.oid = idx.relam
            LEFT JOIN pg_catalog.pg_stat_user_indexes s ON s.indexrelid = idx.oid
            LEFT JOIN pg_catalog.pg_stat_database d ON d.datid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
            WHERE ns.nspname = :schema AND tbl.relname = :table
            ORDER BY idx.relname
            LIMIT :limit
            """, Map.of("schema", schema, "table", table, "limit", limit));
    }

    public List<Map<String, Object>> extendedStatistics(String schema, String table, int limit) {
        return normalized(jdbc.queryForList("""
            SELECT s.statistics_schemaname AS "statisticsSchema", s.statistics_name AS "name",
                   s.schemaname AS "tableSchema", s.tablename AS "table",
                   s.attnames AS "columns", s.exprs AS "expressions", s.kinds AS "kinds",
                   s.n_distinct AS "nDistinct", s.dependencies AS "dependencies",
                   (s.most_common_vals IS NOT NULL) AS "mostCommonValuesAvailable"
            FROM pg_catalog.pg_stats_ext s
            WHERE s.schemaname = :schema AND s.tablename = :table
            ORDER BY s.statistics_schemaname, s.statistics_name
            LIMIT :limit
            """, Map.of("schema", schema, "table", table, "limit", limit)));
    }

    public List<Map<String, Object>> queryStatistics(Long queryId, Long databaseId, Long userId,
            Long minCalls, Double minTotalExecutionMs, int limit) {
        Map<String, Object> extension = jdbc.queryForMap("""
            SELECT n.nspname, c.oid AS view_oid FROM pg_catalog.pg_extension e
            JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
            JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid AND c.relname = 'pg_stat_statements'
            WHERE e.extname = 'pg_stat_statements'
            """, Map.of());
        String view = quote(String.valueOf(extension.get("nspname"))) + ".pg_stat_statements";
        Set<String> columns = new HashSet<>(jdbc.queryForList("""
            SELECT attname FROM pg_catalog.pg_attribute
            WHERE attrelid = :oid AND attnum > 0 AND NOT attisdropped
            """, Map.of("oid", extension.get("view_oid")), String.class));
        String totalExecution = columns.contains("total_exec_time") ? "total_exec_time" : "total_time";
        String meanExecution = columns.contains("mean_exec_time") ? "mean_exec_time" : "mean_time";
        String sql = """
            SELECT queryid AS "queryId", dbid AS "databaseId", userid AS "userId", %s,
                   calls, %s, %s, %s,
                   %s AS "totalExecutionTimeMs", %s AS "meanExecutionTimeMs",
                   %s, %s, rows,
                   shared_blks_hit AS "sharedBlocksHit", shared_blks_read AS "sharedBlocksRead",
                   shared_blks_dirtied AS "sharedBlocksDirtied", shared_blks_written AS "sharedBlocksWritten",
                   local_blks_hit AS "localBlocksHit", local_blks_read AS "localBlocksRead",
                   temp_blks_read AS "tempBlocksRead", temp_blks_written AS "tempBlocksWritten",
                   blk_read_time AS "blockReadTimeMs", blk_write_time AS "blockWriteTimeMs",
                   %s, %s, %s, %s, %s
            FROM %s
            WHERE (:query_id IS NULL OR queryid = :query_id)
              AND (:database_id IS NULL OR dbid = :database_id)
              AND (:user_id IS NULL OR userid = :user_id)
              AND calls >= :min_calls AND %s >= :min_total
            ORDER BY %s DESC, queryid
            LIMIT :limit
            """.formatted(
                optional(columns, "toplevel", "topLevel", "NULL::boolean"),
                optional(columns, "plans", "plans", "NULL::bigint"),
                optional(columns, "total_plan_time", "totalPlanTimeMs", "NULL::double precision"),
                optional(columns, "mean_plan_time", "meanPlanTimeMs", "NULL::double precision"),
                totalExecution, meanExecution,
                optional(columns, "min_exec_time", "minExecutionTimeMs", "NULL::double precision"),
                optional(columns, "max_exec_time", "maxExecutionTimeMs", "NULL::double precision"),
                optional(columns, "wal_records", "walRecords", "NULL::bigint"),
                optional(columns, "wal_fpi", "walFullPageImages", "NULL::bigint"),
                optional(columns, "wal_bytes", "walBytes", "NULL::numeric"),
                optional(columns, "jit_functions", "jitFunctions", "NULL::bigint"),
                optional(columns, "jit_generation_time", "jitGenerationTimeMs", "NULL::double precision"),
                view, totalExecution, totalExecution);
        return jdbc.queryForList(sql, new MapSqlParameterSource()
            .addValue("query_id", queryId).addValue("database_id", databaseId).addValue("user_id", userId)
            .addValue("min_calls", minCalls == null ? 0L : minCalls)
            .addValue("min_total", minTotalExecutionMs == null ? 0D : minTotalExecutionMs)
            .addValue("limit", limit));
    }

    public List<Map<String, Object>> activeQueries(long minDurationMs, boolean includeIdle,
                                                    boolean includeOwn, int limit) {
        return normalized(jdbc.queryForList("""
            SELECT a.pid, a.datid AS "databaseId", a.datname AS "database", a.usesysid AS "userId",
                   a.usename AS "user", a.state, a.backend_type AS "backendType",
                   a.wait_event_type AS "waitEventType", a.wait_event AS "waitEvent",
                   EXTRACT(EPOCH FROM (clock_timestamp() - a.query_start)) * 1000 AS "queryDurationMs",
                   EXTRACT(EPOCH FROM (clock_timestamp() - a.xact_start)) * 1000 AS "transactionDurationMs",
                   EXTRACT(EPOCH FROM (clock_timestamp() - a.state_change)) * 1000 AS "stateDurationMs",
                   pg_catalog.pg_blocking_pids(a.pid) AS "blockingPids",
                   cardinality(pg_catalog.pg_blocking_pids(a.pid)) AS "blockingCount",
                   (a.state LIKE 'idle%') AS "idle"
            FROM pg_catalog.pg_stat_activity a
            WHERE (:include_own OR a.pid <> pg_backend_pid())
              AND (:include_idle OR a.state NOT LIKE 'idle%')
              AND COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - a.query_start)) * 1000, 0) >= :min_duration
            ORDER BY a.query_start NULLS LAST, a.pid
            LIMIT :limit
            """, new MapSqlParameterSource().addValue("include_own", includeOwn)
                .addValue("include_idle", includeIdle).addValue("min_duration", minDurationMs)
                .addValue("limit", limit)));
    }

    private static List<Map<String, Object>> normalized(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((key, value) -> copy.put(key, arrayValue(value)));
            result.add(copy);
        }
        return result;
    }

    private static Object arrayValue(Object value) {
        if (!(value instanceof Array array)) return value;
        try {
            Object raw = array.getArray();
            return raw instanceof Object[] values ? Arrays.asList(values) : raw;
        } catch (SQLException exception) {
            return List.of();
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String optional(Set<String> columns, String column, String alias, String fallback) {
        return (columns.contains(column) ? column : fallback) + " AS \"" + alias + "\"";
    }
}
