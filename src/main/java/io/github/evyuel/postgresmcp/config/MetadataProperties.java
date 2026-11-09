package io.github.evyuel.postgresmcp.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.metadata")
public class MetadataProperties {
    @Min(1) @Max(10_000)
    private int maxObjects = 500;
    @Min(1) @Max(300)
    private int queryTimeoutSeconds = 10;

    public int getMaxObjects() { return maxObjects; }
    public void setMaxObjects(int maxObjects) { this.maxObjects = maxObjects; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
}

