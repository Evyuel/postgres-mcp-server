package io.github.evyuel.postgresmcp;

import io.github.evyuel.postgresmcp.config.MetadataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MetadataProperties.class)
public class PostgresMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PostgresMcpServerApplication.class, args);
    }
}

