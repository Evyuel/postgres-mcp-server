package io.github.evyuel.postgresmcp.exception;

public class MetadataException extends RuntimeException {
    public MetadataException(String message) { super(message); }
    public MetadataException(String message, Throwable cause) { super(message, cause); }
}

