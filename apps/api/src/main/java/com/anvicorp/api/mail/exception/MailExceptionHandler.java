package com.anvicorp.api.mail.exception;

import com.anvicorp.api.common.ErrorResponse;
import com.anvicorp.api.common.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Advice scoped to {@code com.anvicorp.api.mail} ONLY. It handles the
 * mail-specific exception types ({@link MailAuthException} and
 * {@link MailApiException}) plus bean-validation failures from mail controllers,
 * and emits the shared {@link ErrorResponse} JSON contract (with trace id).
 *
 * <p>Because it is package-scoped it never applies to non-mail controllers, and
 * {@code @Order} HIGHEST guarantees it wins for the mail exception types. There
 * is no global exception handler in this backend yet, so the mail module owns a
 * complete, self-consistent error contract for its own surface.</p>
 */
@RestControllerAdvice(basePackages = "com.anvicorp.api.mail")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MailExceptionHandler {

    @ExceptionHandler(MailAuthException.class)
    public ResponseEntity<ErrorResponse> handle(MailAuthException ex) {
        return toResponse(ex.getStatus(), ex.getMessage(), ex.getCode(), null);
    }

    @ExceptionHandler(MailApiException.class)
    public ResponseEntity<ErrorResponse> handle(MailApiException ex) {
        return toResponse(ex.getStatus(), ex.getMessage(), ex.getCode(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return toResponse(HttpStatus.BAD_REQUEST, "Validation failed", "MAIL_VALIDATION_FAILED",
                fields.isEmpty() ? null : fields);
    }

    private ResponseEntity<ErrorResponse> toResponse(HttpStatus status, String message, String code,
                                                     Map<String, Object> details) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        ErrorResponse er = new ErrorResponse(
                status.value(),
                message,   // legacy `error` field
                message,   // canonical `message`
                code,
                traceId,
                Instant.now(),
                details);
        return ResponseEntity.status(status).body(er);
    }
}
