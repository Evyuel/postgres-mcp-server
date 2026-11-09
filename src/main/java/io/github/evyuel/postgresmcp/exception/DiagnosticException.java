package io.github.evyuel.postgresmcp.exception;

import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.Status;

public class DiagnosticException extends RuntimeException {
    private final Status status;

    public DiagnosticException(Status status, String message) {
        super(message);
        this.status = status;
    }

    public DiagnosticException(Status status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public Status getStatus() { return status; }
}
