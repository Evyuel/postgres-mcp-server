package io.github.evyuel.postgresmcp.dto;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public final class DiagnosticsModels {
    private DiagnosticsModels() {}

    public enum Status {
        SUCCESS, INVALID_ARGUMENT, NON_SELECT_STATEMENT, INVALID_EXPLAIN_OPTIONS,
        TIMEOUT, CANCELLED, CAPACITY_EXCEEDED, INSUFFICIENT_PRIVILEGES,
        FEATURE_UNAVAILABLE, RESULT_TOO_LARGE, DATABASE_ERROR
    }

    public record ToolError(String code, String message) {}

    public record ToolResponse<T>(Status status, T data, List<String> warnings,
                                  ToolError error, boolean truncated) {
        public static <T> ToolResponse<T> success(T data) {
            return new ToolResponse<>(Status.SUCCESS, data, List.of(), null, false);
        }
        public static <T> ToolResponse<T> success(T data, List<String> warnings, boolean truncated) {
            return new ToolResponse<>(Status.SUCCESS, data, List.copyOf(warnings), null, truncated);
        }
        public static <T> ToolResponse<T> failure(Status status, String message) {
            return new ToolResponse<>(status, null, List.of(), new ToolError(status.name(), message), false);
        }
    }

    public record ExplainResult(JsonNode plan, boolean analyzed, int timeoutSeconds,
                                Map<String, Boolean> options, int planBytes) {}

    public record CapabilityInfo(String postgresVersion, int serverVersionNumber,
                                 boolean postgres14, boolean pgStatStatements,
                                 boolean trackCounts, boolean trackActivities,
                                 boolean trackIoTiming, String computeQueryId,
                                 List<String> warnings) {}

    public record StatisticsResult(CapabilityInfo capabilities,
                                   List<Map<String, Object>> items) {}
}
