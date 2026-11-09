package io.github.evyuel.postgresmcp.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.diagnostics")
public class DiagnosticsProperties {
    @Min(1) @Max(2)
    private int maxConcurrent = 2;
    @Min(1) @Max(300)
    private int maxTimeoutSeconds = 300;
    @Min(1_024) @Max(2 * 1_024 * 1_024)
    private int maxSqlBytes = 2 * 1_024 * 1_024;
    @Min(1_024) @Max(16 * 1_024 * 1_024)
    private int maxPlanBytes = 16 * 1_024 * 1_024;
    @Min(1_024) @Max(20 * 1_024 * 1_024)
    private int maxResponseBytes = 20 * 1_024 * 1_024;
    @Min(1) @Max(60)
    private int lockTimeoutSeconds = 10;
    @Min(1) @Max(500)
    private int defaultLimit = 50;
    @Min(1) @Max(500)
    private int maxLimit = 500;
    @Min(1) @Max(1_000)
    private int maxTypeItems = 1_000;
    @Min(1) @Max(8)
    private int maxTypeDepth = 4;

    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
    public void setMaxTimeoutSeconds(int maxTimeoutSeconds) { this.maxTimeoutSeconds = maxTimeoutSeconds; }
    public int getMaxSqlBytes() { return maxSqlBytes; }
    public void setMaxSqlBytes(int maxSqlBytes) { this.maxSqlBytes = maxSqlBytes; }
    public int getMaxPlanBytes() { return maxPlanBytes; }
    public void setMaxPlanBytes(int maxPlanBytes) { this.maxPlanBytes = maxPlanBytes; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getLockTimeoutSeconds() { return lockTimeoutSeconds; }
    public void setLockTimeoutSeconds(int lockTimeoutSeconds) { this.lockTimeoutSeconds = lockTimeoutSeconds; }
    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }
    public int getMaxTypeItems() { return maxTypeItems; }
    public void setMaxTypeItems(int maxTypeItems) { this.maxTypeItems = maxTypeItems; }
    public int getMaxTypeDepth() { return maxTypeDepth; }
    public void setMaxTypeDepth(int maxTypeDepth) { this.maxTypeDepth = maxTypeDepth; }
}
