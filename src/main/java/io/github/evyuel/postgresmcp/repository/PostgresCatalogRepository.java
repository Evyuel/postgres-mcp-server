package io.github.evyuel.postgresmcp.repository;

import static io.github.evyuel.postgresmcp.dto.MetadataModels.*;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresCatalogRepository {
    private static final String USER_SCHEMA_PREDICATE = """
        n.nspname <> 'information_schema'
        AND n.nspname <> 'pg_catalog'
        AND n.nspname <> 'pg_toast'
        AND n.nspname !~ '^pg_(temp|toast_temp)_'
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SchemaInfo> listSchemas(List<String> allowlist, int limit) {
        var sql = """
            SELECT n.nspname, pg_get_userbyid(n.nspowner) AS owner,
                   obj_description(n.oid, 'pg_namespace') AS comment
            FROM pg_catalog.pg_namespace n
            WHERE %s AND has_schema_privilege(n.oid, 'USAGE') %s
            ORDER BY n.nspname
            LIMIT :limit
            """.formatted(USER_SCHEMA_PREDICATE, allowlistClause("n.nspname", allowlist));
        return jdbc.query(sql, parameters(allowlist, limit), (rs, row) ->
            new SchemaInfo(rs.getString("nspname"), rs.getString("owner"), rs.getString("comment"), false));
    }

    public boolean schemaExists(String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_namespace n
                          WHERE n.nspname = :schema AND has_schema_privilege(n.oid, 'USAGE'))
            """, Map.of("schema", schema), Boolean.class));
    }

    public List<TableInfo> listTables(String schema, int limit) {
        return jdbc.query("""
            SELECT n.nspname, c.relname, c.relkind, pg_get_userbyid(c.relowner) AS owner,
                   obj_description(c.oid, 'pg_class') AS comment,
                   CASE WHEN c.reltuples >= 0 THEN c.reltuples::bigint END AS estimated_rows,
                   c.relispartition
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = :schema AND c.relkind IN ('r','p','v','m','f')
              AND has_schema_privilege(n.oid, 'USAGE')
            ORDER BY c.relname
            LIMIT :limit
            """, Map.of("schema", schema, "limit", limit), (rs, row) -> new TableInfo(
                rs.getString("nspname"), rs.getString("relname"), objectType(rs.getString("relkind")),
                rs.getString("owner"), rs.getString("comment"), nullableLong(rs, "estimated_rows"),
                nullableLong(rs, "estimated_rows") != null, "p".equals(rs.getString("relkind")),
                rs.getBoolean("relispartition")));
    }

    public RelationRef findRelation(String schema, String table) {
        return jdbc.query("""
            SELECT c.oid, n.nspname, c.relname, c.relkind, c.relispartition,
                   obj_description(c.oid, 'pg_class') AS comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = :schema AND c.relname = :table
              AND c.relkind IN ('r','p','v','m','f')
              AND has_schema_privilege(n.oid, 'USAGE')
            """, Map.of("schema", schema, "table", table), rs -> rs.next()
                ? new RelationRef(rs.getLong("oid"), rs.getString("nspname"), rs.getString("relname"),
                    objectType(rs.getString("relkind")), rs.getString("relkind"),
                    rs.getBoolean("relispartition"), rs.getString("comment"))
                : null);
    }

    public List<ColumnInfo> listColumns(long relationOid) {
        return jdbc.query("""
            SELECT a.attname, a.attnum, pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                   NOT a.attnotnull AS nullable, pg_get_expr(d.adbin, d.adrelid) AS default_expression,
                   a.attidentity, a.attgenerated, col_description(a.attrelid, a.attnum) AS comment,
                   CASE WHEN a.attcollation <> t.typcollation THEN coll.collname END AS collation,
                   EXISTS (SELECT 1 FROM pg_catalog.pg_constraint pk
                           WHERE pk.conrelid = a.attrelid AND pk.contype = 'p'
                             AND a.attnum = ANY(pk.conkey)) AS primary_key
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            LEFT JOIN pg_catalog.pg_collation coll ON coll.oid = a.attcollation
            WHERE a.attrelid = :oid AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """, Map.of("oid", relationOid), (rs, row) -> new ColumnInfo(
                rs.getString("attname"), rs.getInt("attnum"), rs.getString("data_type"),
                rs.getBoolean("nullable"), rs.getString("default_expression"),
                identity(rs.getString("attidentity")), generated(rs.getString("attgenerated")),
                rs.getString("comment"), rs.getString("collation"), rs.getBoolean("primary_key")));
    }

    public List<ConstraintRow> listConstraints(long relationOid) {
        return jdbc.query("""
            SELECT con.conname, con.contype, pg_get_constraintdef(con.oid, true) AS definition,
                   ARRAY(SELECT a.attname
                         FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                         JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
                         ORDER BY k.ord) AS columns
            FROM pg_catalog.pg_constraint con
            WHERE con.conrelid = :oid AND con.contype IN ('p','u','c')
            ORDER BY con.contype, con.conname
            """, Map.of("oid", relationOid), (rs, row) -> new ConstraintRow(
                rs.getString("conname"), rs.getString("contype"), stringList(rs, "columns"),
                rs.getString("definition")));
    }

    public PartitioningInfo partitioning(long relationOid) {
        return jdbc.query("""
            SELECT CASE pt.partstrat WHEN 'r' THEN 'RANGE' WHEN 'l' THEN 'LIST' WHEN 'h' THEN 'HASH' END AS strategy,
                   pg_get_partkeydef(c.oid) AS key_definition, c.relispartition,
                   pn.nspname AS parent_schema, parent.relname AS parent_table,
                   pg_get_expr(c.relpartbound, c.oid, true) AS partition_bound
            FROM pg_catalog.pg_class c
            LEFT JOIN pg_catalog.pg_partitioned_table pt ON pt.partrelid = c.oid
            LEFT JOIN pg_catalog.pg_inherits i ON i.inhrelid = c.oid
            LEFT JOIN pg_catalog.pg_class parent ON parent.oid = i.inhparent
            LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid = parent.relnamespace
            WHERE c.oid = :oid
            """, Map.of("oid", relationOid), rs -> {
                if (!rs.next()) return null;
                boolean partition = rs.getBoolean("relispartition");
                String strategy = rs.getString("strategy");
                if (!partition && strategy == null) return null;
                return new PartitioningInfo(strategy, rs.getString("key_definition"), partition,
                    rs.getString("parent_schema"), rs.getString("parent_table"),
                    rs.getString("partition_bound"));
            });
    }

    public String viewDefinition(long relationOid) {
        return jdbc.queryForObject("SELECT pg_get_viewdef(:oid, true)", Map.of("oid", relationOid), String.class);
    }

    public List<IndexInfo> listIndexes(long relationOid, int limit) {
        return jdbc.query("""
            SELECT idx.relname AS index_name, am.amname, i.indisunique, i.indisprimary,
                   i.indisvalid, i.indisready,
                   ARRAY(SELECT pg_get_indexdef(i.indexrelid, pos, true)
                         FROM generate_series(1, i.indnkeyatts) pos) AS key_parts,
                   ARRAY(SELECT pg_get_indexdef(i.indexrelid, pos, true)
                         FROM generate_series(i.indnkeyatts + 1, i.indnatts) pos) AS include_parts,
                   pg_get_expr(i.indpred, i.indrelid, true) AS predicate,
                   pg_get_indexdef(i.indexrelid) AS definition,
                   pg_relation_size(i.indexrelid) AS size_bytes,
                   obj_description(i.indexrelid, 'pg_class') AS comment
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
            JOIN pg_catalog.pg_am am ON am.oid = idx.relam
            WHERE i.indrelid = :oid
            ORDER BY idx.relname
            LIMIT :limit
            """, Map.of("oid", relationOid, "limit", limit), (rs, row) -> new IndexInfo(
                rs.getString("index_name"), rs.getString("amname"), rs.getBoolean("indisunique"),
                rs.getBoolean("indisprimary"), rs.getBoolean("indisvalid"), rs.getBoolean("indisready"),
                stringList(rs, "key_parts"), stringList(rs, "include_parts"), rs.getString("predicate"),
                rs.getString("definition"), nullableLong(rs, "size_bytes"), rs.getString("comment")));
    }

    public List<ForeignKeyInfo> listForeignKeys(long relationOid, int limit) {
        return jdbc.query("""
            SELECT con.conname, sn.nspname AS source_schema, src.relname AS source_table,
                   tn.nspname AS target_schema, tgt.relname AS target_table,
                   ARRAY(SELECT sa.attname FROM generate_subscripts(con.conkey, 1) s
                         JOIN pg_catalog.pg_attribute sa ON sa.attrelid = con.conrelid AND sa.attnum = con.conkey[s]
                         ORDER BY s) AS source_columns,
                   ARRAY(SELECT ta.attname FROM generate_subscripts(con.confkey, 1) s
                         JOIN pg_catalog.pg_attribute ta ON ta.attrelid = con.confrelid AND ta.attnum = con.confkey[s]
                         ORDER BY s) AS target_columns,
                   con.confupdtype, con.confdeltype, con.confmatchtype,
                   con.condeferrable, con.condeferred, con.convalidated,
                   pg_get_constraintdef(con.oid, true) AS definition
            FROM pg_catalog.pg_constraint con
            JOIN pg_catalog.pg_class src ON src.oid = con.conrelid
            JOIN pg_catalog.pg_namespace sn ON sn.oid = src.relnamespace
            JOIN pg_catalog.pg_class tgt ON tgt.oid = con.confrelid
            JOIN pg_catalog.pg_namespace tn ON tn.oid = tgt.relnamespace
            WHERE con.conrelid = :oid AND con.contype = 'f'
            ORDER BY con.conname
            LIMIT :limit
            """, Map.of("oid", relationOid, "limit", limit), (rs, row) -> new ForeignKeyInfo(
                rs.getString("conname"), rs.getString("source_schema"), rs.getString("source_table"),
                stringList(rs, "source_columns"), rs.getString("target_schema"), rs.getString("target_table"),
                stringList(rs, "target_columns"), action(rs.getString("confupdtype")),
                action(rs.getString("confdeltype")), match(rs.getString("confmatchtype")),
                rs.getBoolean("condeferrable"), rs.getBoolean("condeferred"), rs.getBoolean("convalidated"),
                rs.getString("definition")));
    }

    public List<FunctionInfo> listFunctions(String schema, int limit) {
        return jdbc.query("""
            SELECT n.nspname, p.proname, p.prokind,
                   pg_get_function_identity_arguments(p.oid) AS identity_arguments,
                   pg_get_function_arguments(p.oid) AS signature,
                   ARRAY(SELECT pg_catalog.format_type(arg_oid, NULL)
                         FROM unnest(p.proargtypes) arg_oid) AS argument_types,
                   pg_get_function_result(p.oid) AS return_type, l.lanname,
                   p.provolatile, p.prosecdef, obj_description(p.oid, 'pg_proc') AS comment
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            JOIN pg_catalog.pg_language l ON l.oid = p.prolang
            WHERE n.nspname = :schema AND p.prokind IN ('f','p')
              AND has_schema_privilege(n.oid, 'USAGE')
              AND has_function_privilege(p.oid, 'EXECUTE')
            ORDER BY p.proname, pg_get_function_identity_arguments(p.oid)
            LIMIT :limit
            """, Map.of("schema", schema, "limit", limit), (rs, row) -> new FunctionInfo(
                rs.getString("nspname"), rs.getString("proname"),
                "p".equals(rs.getString("prokind")) ? "PROCEDURE" : "FUNCTION",
                rs.getString("identity_arguments"), rs.getString("signature"),
                stringList(rs, "argument_types"), rs.getString("return_type"), rs.getString("lanname"),
                volatility(rs.getString("provolatile")), rs.getBoolean("prosecdef"), rs.getString("comment")));
    }

    private static MapSqlParameterSource parameters(List<String> allowlist, int limit) {
        return new MapSqlParameterSource().addValue("allowlist", allowlist).addValue("limit", limit);
    }

    private static String allowlistClause(String column, List<String> allowlist) {
        return allowlist.isEmpty() ? "" : "AND " + column + " IN (:allowlist)";
    }

    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static String objectType(String relkind) {
        return switch (relkind) {
            case "r" -> "TABLE"; case "p" -> "PARTITIONED_TABLE"; case "v" -> "VIEW";
            case "m" -> "MATERIALIZED_VIEW"; case "f" -> "FOREIGN_TABLE";
            default -> "OTHER";
        };
    }
    private static String identity(String value) {
        return switch (value == null ? "" : value) { case "a" -> "ALWAYS"; case "d" -> "BY_DEFAULT"; default -> null; };
    }
    private static String generated(String value) {
        return switch (value == null ? "" : value) { case "s" -> "STORED"; case "v" -> "VIRTUAL"; default -> null; };
    }
    private static String volatility(String value) {
        return switch (value) { case "i" -> "IMMUTABLE"; case "s" -> "STABLE"; default -> "VOLATILE"; };
    }
    private static String action(String value) {
        return switch (value) { case "a" -> "NO_ACTION"; case "r" -> "RESTRICT"; case "c" -> "CASCADE";
            case "n" -> "SET_NULL"; case "d" -> "SET_DEFAULT"; default -> "UNKNOWN"; };
    }
    private static String match(String value) {
        return switch (value) { case "f" -> "FULL"; case "p" -> "PARTIAL"; default -> "SIMPLE"; };
    }

    public record RelationRef(long oid, String schema, String name, String objectType, String relkind,
                              boolean partition, String comment) {}
    public record ConstraintRow(String name, String type, List<String> columns, String definition) {}
}

