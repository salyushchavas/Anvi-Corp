package com.anvicorp.api.mail.exception;

import org.springframework.http.HttpStatus;

/**
 * Mail-module auth/validation failure. Carries an HTTP status + a stable
 * machine-readable code. Handled by {@link MailExceptionHandler} (scoped to the
 * mail package) which renders it as the shared
 * {@code com.anvicorp.api.exception.ErrorResponse} JSON shape.
 */
public class MailAuthException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MailAuthException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
