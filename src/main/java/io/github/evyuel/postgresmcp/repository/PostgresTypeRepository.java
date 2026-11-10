package io.github.evyuel.postgresmcp.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresTypeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PostgresTypeRepository(NamedParameterJdbcTemplate metadataJdbcTemplate) { this.jdbc = metadataJdbcTemplate; }

    public Long findTypeOid(String schema, String type) {
        return jdbc.query("""
            SELECT t.oid FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = :schema AND t.typname = :type
            """, Map.of("schema", schema, "type", type), rs -> rs.next() ? rs.getLong(1) : null);
    }

    public Map<Long, TypeRow> loadTypes(Collection<Long> oids) {
        if (oids.isEmpty()) return Map.of();
        List<TypeRow> rows = jdbc.query("""
            SELECT t.oid, n.nspname, t.typname, t.typtype, t.typcategory, t.typelem,
                   t.typbasetype, t.typrelid, t.typnotnull, t.typdefault,
                   pg_catalog.format_type(t.oid, NULL) AS formatted_name,
                   CASE WHEN t.typbasetype <> 0 THEN pg_catalog.format_type(t.typbasetype, t.typtypmod) END AS base_type,
                   CASE WHEN t.typcollation <> 0 THEN cn.nspname END AS collation_schema,
                   CASE WHEN t.typcollation <> 0 THEN coll.collname END AS collation_name
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            LEFT JOIN pg_catalog.pg_collation coll ON coll.oid = t.typcollation
            LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = coll.collnamespace
            WHERE t.oid IN (:oids)
            """, Map.of("oids", oids), (rs, row) -> new TypeRow(
                rs.getLong("oid"), rs.getString("nspname"), rs.getString("typname"),
                rs.getString("typtype"), rs.getString("typcategory"), rs.getLong("typelem"),
                rs.getLong("typbasetype"), rs.getLong("typrelid"), rs.getBoolean("typnotnull"),
                rs.getString("typdefault"), rs.getString("formatted_name"), rs.getString("base_type"),
                rs.getString("collation_schema"), rs.getString("collation_name")));
        Map<Long, TypeRow> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.oid(), row));
        return result;
    }

    public Map<Long, List<String>> enumLabels(Collection<Long> oids, int limitPerType) {
        if (oids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Long, List<String>>();
        jdbc.query("""
            SELECT e.enumtypid, e.enumlabel FROM pg_catalog.pg_enum e
            WHERE e.enumtypid IN (:oids)
            ORDER BY e.enumtypid, e.enumsortorder
            """, Map.of("oids", oids), rs -> {
                long oid = rs.getLong("enumtypid");
                List<String> labels = result.computeIfAbsent(oid, ignored -> new ArrayList<>());
                if (labels.size() <= limitPerType) labels.add(rs.getString("enumlabel"));
            });
        return result;
    }

    public Map<Long, List<String>> domainConstraints(Collection<Long> oids) {
        if (oids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Long, List<String>>();
        jdbc.query("""
            SELECT c.contypid,
                   'CONSTRAINT ' || quote_ident(c.conname) || ' ' || pg_get_constraintdef(c.oid, true) AS definition
            FROM pg_catalog.pg_constraint c
            WHERE c.contypid IN (:oids) ORDER BY c.contypid, c.conname
            """, Map.of("oids", oids), rs -> {
                result.computeIfAbsent(rs.getLong("contypid"), ignored -> new ArrayList<>())
                    .add(rs.getString("definition"));
            });
        return result;
    }

    public Map<Long, List<AttributeRow>> compositeAttributes(Collection<Long> relationOids) {
        if (relationOids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Long, List<AttributeRow>>();
        jdbc.query("""
            SELECT a.attrelid, a.attname, a.atttypid, a.attnotnull,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type
            FROM pg_catalog.pg_attribute a
            WHERE a.attrelid IN (:oids) AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attrelid, a.attnum
            """, Map.of("oids", relationOids), rs -> {
                result.computeIfAbsent(rs.getLong("attrelid"), ignored -> new ArrayList<>())
                    .add(new AttributeRow(rs.getString("attname"), rs.getLong("atttypid"),
                        rs.getString("data_type"), !rs.getBoolean("attnotnull")));
            });
        return result;
    }

    public Map<Long, RangeRow> ranges(Collection<Long> typeOids) {
        if (typeOids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Long, RangeRow>();
        jdbc.query("""
            SELECT r.rngtypid, r.rngsubtype, r.rngmultitypid,
                   pg_catalog.format_type(r.rngsubtype, NULL) AS subtype,
                   mt.oid AS multirange_oid,
                   CASE WHEN mt.oid IS NULL THEN NULL ELSE pg_catalog.format_type(mt.oid, NULL) END AS multirange,
                   opc.opcname AS subtype_operator_class,
                   CASE WHEN r.rngcanonical = 0 THEN NULL ELSE r.rngcanonical::regprocedure::text END AS canonical_function,
                   CASE WHEN r.rngsubdiff = 0 THEN NULL ELSE r.rngsubdiff::regprocedure::text END AS subtype_diff_function
            FROM pg_catalog.pg_range r
            LEFT JOIN pg_catalog.pg_type mt ON mt.oid = r.rngmultitypid
            LEFT JOIN pg_catalog.pg_opclass opc ON opc.oid = r.rngsubopc
            WHERE r.rngtypid IN (:oids) OR r.rngmultitypid IN (:oids)
            """, Map.of("oids", typeOids), rs -> {
                var row = new RangeRow(rs.getLong("rngtypid"), rs.getLong("rngsubtype"),
                    rs.getLong("multirange_oid"), rs.getString("subtype"), rs.getString("multirange"),
                    rs.getString("subtype_operator_class"), rs.getString("canonical_function"),
                    rs.getString("subtype_diff_function"));
                result.put(row.rangeTypeOid(), row);
                if (row.multirangeTypeOid() != 0) result.put(row.multirangeTypeOid(), row);
            });
        return result;
    }

    public record TypeRow(long oid, String schema, String name, String typeKind, String category,
                          long elementTypeOid, long baseTypeOid, long relationOid, boolean notNull,
                          String defaultExpression, String formattedName, String baseType,
                          String collationSchema, String collationName) {}
    public record AttributeRow(String name, long typeOid, String dataType, boolean nullable) {}
    public record RangeRow(long rangeTypeOid, long subtypeOid, long multirangeTypeOid,
                           String subtype, String multirangeType, String subtypeOperatorClass,
                           String canonicalFunction, String subtypeDiffFunction) {}
}
