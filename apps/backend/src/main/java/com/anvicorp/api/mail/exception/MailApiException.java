package com.anvicorp.api.mail.exception;

import org.springframework.http.HttpStatus;

/**
 * General mail admin-API failure (404/409/400/403). Same shape as
 * {@link MailAuthException} (HTTP status + stable code) but named for the
 * admin/provisioning surface. Handled by {@link MailExceptionHandler} → the
 * shared {@code com.anvicorp.api.exception.ErrorResponse} JSON contract.
 */
public class MailApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MailApiException(HttpStatus status, String message, String code) {
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
