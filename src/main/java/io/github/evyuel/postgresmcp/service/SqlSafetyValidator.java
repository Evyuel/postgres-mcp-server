package io.github.evyuel.postgresmcp.service;

import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.Status;
import io.github.evyuel.postgresmcp.exception.DiagnosticException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlSafetyValidator {
    private static final Pattern FIRST_WORD = Pattern.compile("[A-Za-z_][A-Za-z_0-9$]*");
    private static final Pattern FORBIDDEN_WORD = Pattern.compile(
        "(?i)\\b(insert|update|delete|merge|create|alter|drop|truncate|copy|call|do|grant|revoke|refresh|reindex|cluster|into)\\b");

    private SqlSafetyValidator() {}

    static String validate(String sql, int maxBytes) {
        if (sql == null || sql.isBlank()) {
            throw new DiagnosticException(Status.INVALID_ARGUMENT, "sql must not be blank");
        }
        if (sql.indexOf('\0') >= 0) {
            throw new DiagnosticException(Status.INVALID_ARGUMENT, "sql contains a NUL character");
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new DiagnosticException(Status.INVALID_ARGUMENT, "sql exceeds the 2 MiB UTF-8 limit");
        }
        String visible = maskQuotedAndComments(sql);
        int semicolon = visible.indexOf(';');
        if (semicolon >= 0 && !visible.substring(semicolon + 1).isBlank()) {
            throw new DiagnosticException(Status.NON_SELECT_STATEMENT, "multiple SQL statements are not allowed");
        }
        if (semicolon >= 0 && visible.indexOf(';', semicolon + 1) >= 0) {
            throw new DiagnosticException(Status.NON_SELECT_STATEMENT, "multiple SQL statements are not allowed");
        }
        String candidate = semicolon >= 0 ? sql.substring(0, semicolon) : sql;
        Matcher matcher = FIRST_WORD.matcher(visible);
        if (!matcher.find()) {
            throw new DiagnosticException(Status.INVALID_ARGUMENT, "sql does not contain a statement");
        }
        String first = matcher.group().toLowerCase(Locale.ROOT);
        if (!first.equals("select") && !first.equals("with")) {
            throw new DiagnosticException(Status.NON_SELECT_STATEMENT, "only SELECT statements are allowed");
        }
        if (FORBIDDEN_WORD.matcher(visible).find()) {
            throw new DiagnosticException(Status.NON_SELECT_STATEMENT, "data-modifying and utility statements are not allowed");
        }
        return candidate.strip();
    }

    private static String maskQuotedAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                boolean escapeString = quote == '\'' && i > 0
                    && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                    && (i < 2 || !Character.isJavaIdentifierPart(sql.charAt(i - 2)));
                out.append(' ');
                i++;
                while (i < sql.length()) {
                    char q = sql.charAt(i++);
                    out.append(q == '\n' ? '\n' : ' ');
                    if (escapeString && q == '\\' && i < sql.length()) {
                        out.append(sql.charAt(i) == '\n' ? '\n' : ' '); i++; continue;
                    }
                    if (q == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) {
                            out.append(' '); i++;
                        } else break;
                    }
                }
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                out.append("  "); i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int depth = 1; out.append("  "); i += 2;
                while (i < sql.length() && depth > 0) {
                    if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                        depth++; out.append("  "); i += 2;
                    } else if (i + 1 < sql.length() && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                        depth--; out.append("  "); i += 2;
                    } else { out.append(sql.charAt(i) == '\n' ? '\n' : ' '); i++; }
                }
            } else if (c == '$') {
                int end = sql.indexOf('$', i + 1);
                String tag = end >= 0 ? sql.substring(i, end + 1) : "";
                if (tag.matches("\\$[A-Za-z_0-9]*\\$")) {
                    int close = sql.indexOf(tag, end + 1);
                    if (close < 0) close = sql.length() - tag.length();
                    int until = Math.min(sql.length(), close + tag.length());
                    while (i < until) { out.append(sql.charAt(i) == '\n' ? '\n' : ' '); i++; }
                } else { out.append(c); i++; }
            } else { out.append(c); i++; }
        }
        return out.toString();
    }
}
