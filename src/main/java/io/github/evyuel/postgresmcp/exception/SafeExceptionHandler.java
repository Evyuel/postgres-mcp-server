package io.github.evyuel.postgresmcp.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SafeExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(SafeExceptionHandler.class);

    @ExceptionHandler(MetadataException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> metadataError(MetadataException exception) {
        log.warn("Metadata request rejected: {}", exception.getMessage());
        return Map.of("error", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    Map<String, String> unexpected(Exception exception) {
        log.error("Unexpected metadata server error ({})", exception.getClass().getSimpleName());
        return Map.of("error", "Metadata request failed");
    }
}
