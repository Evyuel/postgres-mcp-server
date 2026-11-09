package io.github.evyuel.postgresmcp.service;

import static io.github.evyuel.postgresmcp.dto.DiagnosticsModels.*;
import static io.github.evyuel.postgresmcp.dto.MetadataModels.*;

import io.github.evyuel.postgresmcp.config.DiagnosticsProperties;
import io.github.evyuel.postgresmcp.repository.PostgresTypeRepository;
import io.github.evyuel.postgresmcp.repository.PostgresTypeRepository.AttributeRow;
import io.github.evyuel.postgresmcp.repository.PostgresTypeRepository.RangeRow;
import io.github.evyuel.postgresmcp.repository.PostgresTypeRepository.TypeRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class PostgresTypeService {
    private final PostgresTypeRepository repository;
    private final DiagnosticsProperties properties;

    public PostgresTypeService(PostgresTypeRepository repository, DiagnosticsProperties properties) {
        this.repository = repository; this.properties = properties;
    }

    public ToolResponse<TypeDetails> describe(String schema, String type) {
        try {
            requireName(schema, "schema"); requireName(type, "type");
            Long oid = repository.findTypeOid(schema, type);
            if (oid == null) return ToolResponse.failure(Status.INVALID_ARGUMENT, "type is not visible or does not exist");
            return ToolResponse.success(resolveAll(Set.of(oid)).get(oid));
        } catch (IllegalArgumentException exception) {
            return ToolResponse.failure(Status.INVALID_ARGUMENT, exception.getMessage());
        } catch (DataAccessException exception) {
            return ToolResponse.failure(Status.DATABASE_ERROR, "PostgreSQL type catalog query failed");
        }
    }

    public Map<Long, TypeDetails> resolveAll(Collection<Long> rootOids) {
        Catalog catalog = loadCatalog(rootOids);
        Map<Long, TypeDetails> result = new LinkedHashMap<>();
        for (Long oid : rootOids) result.put(oid, build(oid, catalog, 0, new HashSet<>()));
        return result;
    }

    public List<TypeDependency> dependencies(Collection<TypeDetails> roots) {
        var result = new LinkedHashMap<String, TypeDependency>();
        for (TypeDetails root : roots) collect(root, result, new HashSet<>());
        return List.copyOf(result.values());
    }

    private Catalog loadCatalog(Collection<Long> rootOids) {
        Map<Long, TypeRow> types = new LinkedHashMap<>();
        Map<Long, List<String>> labels = new LinkedHashMap<>();
        Map<Long, List<String>> constraints = new LinkedHashMap<>();
        Map<Long, List<AttributeRow>> attributes = new LinkedHashMap<>();
        Map<Long, RangeRow> ranges = new LinkedHashMap<>();
        Set<Long> frontier = positive(rootOids);
        for (int depth = 0; depth <= properties.getMaxTypeDepth() && !frontier.isEmpty(); depth++) {
            frontier.removeAll(types.keySet());
            if (frontier.isEmpty()) break;
            Map<Long, TypeRow> loaded = repository.loadTypes(frontier);
            types.putAll(loaded);
            Set<Long> ids = loaded.keySet();
            labels.putAll(repository.enumLabels(ids, properties.getMaxTypeItems()));
            constraints.putAll(repository.domainConstraints(ids));
            Set<Long> relationOids = new LinkedHashSet<>();
            loaded.values().stream().filter(row -> row.relationOid() != 0).forEach(row -> relationOids.add(row.relationOid()));
            Map<Long, List<AttributeRow>> byRelation = repository.compositeAttributes(relationOids);
            for (TypeRow row : loaded.values()) if (row.relationOid() != 0) attributes.put(row.oid(), byRelation.getOrDefault(row.relationOid(), List.of()));
            Map<Long, RangeRow> loadedRanges = repository.ranges(ids);
            ranges.putAll(loadedRanges);
            Set<Long> next = new LinkedHashSet<>();
            for (TypeRow row : loaded.values()) {
                add(next, row.elementTypeOid()); add(next, row.baseTypeOid());
                for (AttributeRow attribute : attributes.getOrDefault(row.oid(), List.of())) add(next, attribute.typeOid());
                RangeRow range = ranges.get(row.oid());
                if (range != null) { add(next, range.subtypeOid()); add(next, range.rangeTypeOid()); add(next, range.multirangeTypeOid()); }
            }
            frontier = next;
        }
        return new Catalog(types, labels, constraints, attributes, ranges);
    }

    private TypeDetails build(long oid, Catalog catalog, int depth, Set<Long> path) {
        TypeRow row = catalog.types().get(oid);
        if (row == null) return null;
        String kind = kind(row, catalog.ranges().get(oid));
        if (depth >= properties.getMaxTypeDepth() || !path.add(oid)) {
            return new TypeDetails(oid, row.schema(), row.name(), row.formattedName(), kind,
                DdlCompleteness.INFORMATIONAL, null, List.of(), row.baseType(), row.defaultExpression(),
                row.notNull(), null, List.of(), List.of(), null, null, null, null, null, null,
                List.of("Nested type expansion stopped at the configured depth or a cycle."));
        }
        try {
            List<String> loadedEnumValues = catalog.labels().getOrDefault(oid, List.of());
            boolean enumTruncated = loadedEnumValues.size() > properties.getMaxTypeItems();
            List<String> enumValues = loadedEnumValues.stream().limit(properties.getMaxTypeItems()).toList();
            List<String> domainConstraints = catalog.constraints().getOrDefault(oid, List.of());
            List<TypeAttribute> typeAttributes = catalog.attributes().getOrDefault(oid, List.of()).stream()
                .limit(properties.getMaxTypeItems())
                .map(attribute -> new TypeAttribute(attribute.name(), attribute.dataType(), attribute.nullable(),
                    build(attribute.typeOid(), catalog, depth + 1, path))).toList();
            TypeDetails element = row.elementTypeOid() == 0 ? null : build(row.elementTypeOid(), catalog, depth + 1, path);
            TypeDetails base = row.baseTypeOid() == 0 ? null : build(row.baseTypeOid(), catalog, depth + 1, path);
            RangeRow range = catalog.ranges().get(oid);
            DdlCompleteness completeness = enumTruncated ? DdlCompleteness.INFORMATIONAL : switch (kind) {
                case "ENUM", "DOMAIN" -> DdlCompleteness.STRUCTURAL_COMPLETE;
                case "COMPOSITE", "RANGE", "MULTIRANGE" -> DdlCompleteness.INFORMATIONAL;
                default -> DdlCompleteness.NOT_APPLICABLE;
            };
            String ddl = kind.equals("ENUM") && !enumTruncated ? enumDdl(row, enumValues)
                : kind.equals("DOMAIN") ? domainDdl(row, domainConstraints) : null;
            List<String> limitations = enumTruncated
                ? List.of("ENUM labels exceeded the configured 1000 item limit; no partial DDL is emitted.")
                : completeness == DdlCompleteness.STRUCTURAL_COMPLETE
                ? List.of("Owner, grants and comments are not included; use pg_dump for migration-grade DDL.")
                : completeness == DdlCompleteness.INFORMATIONAL
                    ? List.of("This is an informational structure, not guaranteed migration-ready DDL.") : List.of();
            return new TypeDetails(oid, row.schema(), row.name(), row.formattedName(), kind,
                completeness, ddl, enumValues, row.baseType(), row.defaultExpression(), row.notNull(),
                base, domainConstraints, typeAttributes, range == null ? null : range.subtype(),
                range == null ? null : range.multirangeType(),
                range == null ? null : range.subtypeOperatorClass(),
                range == null ? null : range.canonicalFunction(),
                range == null ? null : range.subtypeDiffFunction(), element, limitations);
        } finally { path.remove(oid); }
    }

    private static String enumDdl(TypeRow row, List<String> labels) {
        return "CREATE TYPE " + qualified(row) + " AS ENUM (" + labels.stream()
            .map(PostgresTypeService::literal).reduce((a, b) -> a + ", " + b).orElse("") + ");";
    }

    private static String domainDdl(TypeRow row, List<String> constraints) {
        StringBuilder ddl = new StringBuilder("CREATE DOMAIN ").append(qualified(row)).append(" AS ").append(row.baseType());
        if (row.collationName() != null) ddl.append(" COLLATE ").append(quote(row.collationSchema())).append('.').append(quote(row.collationName()));
        if (row.defaultExpression() != null) ddl.append(" DEFAULT ").append(row.defaultExpression());
        if (row.notNull()) ddl.append(" NOT NULL");
        for (String constraint : constraints) ddl.append(' ').append(constraint);
        return ddl.append(';').toString();
    }

    private static void collect(TypeDetails type, Map<String, TypeDependency> result, Set<Long> path) {
        if (type == null || type.oid() == null || !path.add(type.oid())) return;
        collect(type.elementType(), result, path);
        collect(type.baseTypeDetails(), result, path);
        for (TypeAttribute attribute : type.attributes()) collect(attribute.typeDetails(), result, path);
        if (!type.schema().equals("pg_catalog") && !type.kind().equals("ARRAY")) {
            String key = type.schema() + "." + type.name();
            result.putIfAbsent(key, new TypeDependency(type.schema(), type.name(), type.kind(),
                type.ddlCompleteness(), type.reconstructedDdl()));
        }
        path.remove(type.oid());
    }

    private static String kind(TypeRow row, RangeRow range) {
        if (row.elementTypeOid() != 0 && "A".equals(row.category())) return "ARRAY";
        return switch (row.typeKind()) {
            case "e" -> "ENUM"; case "d" -> "DOMAIN"; case "c" -> "COMPOSITE";
            case "r" -> "RANGE"; case "m" -> "MULTIRANGE"; case "p" -> "PSEUDO";
            default -> range != null && range.multirangeTypeOid() == row.oid() ? "MULTIRANGE" : "BASE";
        };
    }

    private static Set<Long> positive(Collection<Long> values) {
        Set<Long> result = new LinkedHashSet<>(); values.forEach(value -> add(result, value == null ? 0 : value)); return result;
    }
    private static void add(Collection<Long> values, long value) { if (value != 0) values.add(value); }
    private static String qualified(TypeRow row) { return quote(row.schema()) + "." + quote(row.name()); }
    private static String quote(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    private static String literal(String value) { return '\'' + value.replace("'", "''") + '\''; }
    private static void requireName(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 63 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid " + name + " name");
    }

    private record Catalog(Map<Long, TypeRow> types, Map<Long, List<String>> labels,
                           Map<Long, List<String>> constraints, Map<Long, List<AttributeRow>> attributes,
                           Map<Long, RangeRow> ranges) {}
}
