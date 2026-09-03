package com.anvicorp.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F4 — single error-response contract. Every branch emits an
 * {@link ErrorResponse} carrying the per-request {@code traceId} from
 * {@link TraceIdFilter}, so a user-reported failure (which sees the
 * traceId in the response body + the {@code X-Trace-Id} header) is
 * greppable in one step in the backend log.
 *
 * <p>The legacy {@code error} field is preserved on every response (it
 * duplicates {@link ErrorResponse#message}) so the 191 existing call
 * sites that read {@code response.data.error} keep working unchanged.</p>
 *
 * <p>4xx client errors log at WARN (the caller did something wrong);
 * 5xx + uncaught exceptions log at ERROR with the full exception chain
 * + endpoint + authenticated user, never user-facing.</p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Configured multipart caps — surfaced in the UPLOAD_TOO_LARGE
    // response message so the ERM sees the exact limits they breached
    // instead of a generic "too large" line. Field initializers give
    // sane defaults so a `new GlobalExceptionHandler()` in standalone
    // MockMvc still renders a readable message before Spring injects.
    @Value("${spring.servlet.multipart.max-file-size:10MB}")
    private String maxFileSize = "10MB";

    @Value("${spring.servlet.multipart.max-request-size:60MB}")
    private String maxRequestSize = "60MB";

    // ── Domain exceptions ────────────────────────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, null, ex.getMessage(), null);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, null, ex.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, null, ex.getMessage(), null);
    }

    @ExceptionHandler(LifecycleClosedException.class)
    public ResponseEntity<ErrorResponse> handleLifecycleClosed(LifecycleClosedException ex) {
        return body(HttpStatus.CONFLICT, "LIFECYCLE_CLOSED", ex.getMessage(), null);
    }

    @ExceptionHandler(ReportingStructureIncompleteException.class)
    public ResponseEntity<ErrorResponse> handleReportingStructureIncomplete(
            ReportingStructureIncompleteException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("missing", ex.getMissing());
        return body(HttpStatus.CONFLICT, "REPORTING_STRUCTURE_INCOMPLETE",
                ex.getMessage(), details);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, null, ex.getMessage(), null);
    }

    @ExceptionHandler(com.anvicorp.api.github.GitHubIntegrationException.class)
    public ResponseEntity<ErrorResponse> handleGithubIntegration(
            com.anvicorp.api.github.GitHubIntegrationException ex) {
        return body(HttpStatus.BAD_GATEWAY, "github_call_failed", ex.getMessage(), null);
    }

    @ExceptionHandler(EmailUnverifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailUnverified(EmailUnverifiedException ex) {
        return body(HttpStatus.FORBIDDEN, "EMAIL_UNVERIFIED", ex.getMessage(), null);
    }

    @ExceptionHandler(ProfileIncompleteException.class)
    public ResponseEntity<ErrorResponse> handleProfileIncomplete(ProfileIncompleteException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("missing", ex.getMissing());
        return body(HttpStatus.CONFLICT, "PROFILE_INCOMPLETE", ex.getMessage(), details);
    }

    @ExceptionHandler(OfferRequiredException.class)
    public ResponseEntity<ErrorResponse> handleOfferRequired(OfferRequiredException ex) {
        return body(HttpStatus.FORBIDDEN, "OFFER_REQUIRED", ex.getMessage(), null);
    }

    @ExceptionHandler(I9NotCompleteException.class)
    public ResponseEntity<ErrorResponse> handleI9NotComplete(I9NotCompleteException ex) {
        return body(HttpStatus.FORBIDDEN, "I9_NOT_COMPLETE", ex.getMessage(), null);
    }

    @ExceptionHandler(StemOptRequiredException.class)
    public ResponseEntity<ErrorResponse> handleStemOptRequired(StemOptRequiredException ex) {
        return body(HttpStatus.FORBIDDEN, "STEM_OPT_REQUIRED", ex.getMessage(), null);
    }

    @ExceptionHandler(InterviewRequiredException.class)
    public ResponseEntity<ErrorResponse> handleInterviewRequired(InterviewRequiredException ex) {
        return body(HttpStatus.FORBIDDEN, "INTERVIEW_REQUIRED", ex.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, null, "Access denied", null);
    }

    // ── Request parsing + validation ─────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", fieldErrors);
        String summary = fieldErrors.isEmpty()
                ? "Validation failed"
                : "Validation failed: " + String.join(", ", fieldErrors.keySet());
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", summary, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Constraint violation: " + ex.getMessage(), null);
    }

    /**
     * Jackson deserialization failures + missing request bodies. Before
     * F4 these fell through to the generic {@link Exception} handler
     * and rendered as a bare 500 with no hint that the body was the
     * problem. Now: 400 + a short summary.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        Throwable root = rootCause(ex);
        log.warn("[F4] HttpMessageNotReadable {}: {}",
                root.getClass().getSimpleName(), root.getMessage());
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_BODY",
                "Request body is malformed or missing required fields.", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("[F4] Upload over cap (max-file-size={} max-request-size={}): {}",
                maxFileSize, maxRequestSize, ex.getMessage());
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                "Upload is too large. Each file must be under " + maxFileSize
                        + " and the total request under " + maxRequestSize + ".", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        log.warn("[F4] Unsupported media type: {}", ex.getMessage());
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported content type for this endpoint", null);
    }

    // ── Persistence ──────────────────────────────────────────────────────

    /**
     * F4 — distinguish the SQLState so the user gets an accurate
     * message. The legacy "duplicate or conflicting record" was wrong
     * for CHECK / FK / NOT NULL violations (e.g. the document_tasks
     * CHECK violation showed as "duplicate"). Constraint name + state
     * go to the log, never the user.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable root = rootCause(ex);
        String sqlState = null;
        String constraint = null;
        if (root instanceof SQLException sqlEx) {
            sqlState = sqlEx.getSQLState();
            // Postgres includes the constraint name in the message —
            // capture it for the log so the constraint is greppable.
            String msg = sqlEx.getMessage();
            if (msg != null) {
                int idx = msg.indexOf("constraint \"");
                if (idx >= 0) {
                    int end = msg.indexOf('"', idx + 12);
                    if (end > idx) constraint = msg.substring(idx + 12, end);
                }
            }
        }
        log.warn("[F4] DataIntegrityViolation sqlState={} constraint={} root={}: {}",
                sqlState, constraint,
                root.getClass().getSimpleName(), root.getMessage());

        // Map the well-known Postgres SQLState codes to a friendly,
        // accurate user message. Unknown / driver-specific states fall
        // through to the generic 409.
        // For CHECK violations we can usually name the constraint that
        // tripped (Postgres puts it in the message). Surfacing it in the
        // response detail turns "A submitted value isn't allowed here"
        // from a dead-end into something a developer can grep — the
        // stale weekly_reports_status_check incident that shipped this
        // handler improvement burned two debugging cycles before the
        // constraint name gave it away.
        Map<String, Object> checkDetails = null;
        if ("23514".equals(sqlState) && constraint != null) {
            checkDetails = new LinkedHashMap<>();
            checkDetails.put("constraint", constraint);
        }

        return switch (sqlState == null ? "" : sqlState) {
            case "23505" -> body(HttpStatus.CONFLICT, "UNIQUE_VIOLATION",
                    "A record with these details already exists.", null);
            case "23514" -> body(HttpStatus.BAD_REQUEST, "CHECK_VIOLATION",
                    constraint != null
                            ? "A submitted value isn't allowed here (violates "
                                    + constraint + "). Please review the form and try again."
                            : "A submitted value isn't allowed here. "
                                    + "Please review the form and try again.",
                    checkDetails);
            case "23503" -> body(HttpStatus.CONFLICT, "FK_VIOLATION",
                    "This action can't be completed because related records "
                            + "still exist, or a referenced record is missing.", null);
            case "23502" -> body(HttpStatus.BAD_REQUEST, "NOT_NULL_VIOLATION",
                    "A required field is missing. "
                            + "Please review the form and try again.", null);
            default -> body(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                    "The change conflicts with the current state of the data. "
                            + "Please refresh and try again.", null);
        };
    }

    // ── Routing 404 + catch-all 500 ──────────────────────────────────────

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, null, "Not Found", null);
    }

    /**
     * SSE / long-poll client disconnect. When the browser closes the
     * {@code /api/mail/events} EventSource (tab close, network blip,
     * timeout, page navigation) the async write path throws
     * {@link org.springframework.web.context.request.async.AsyncRequestNotUsableException}
     * (Spring 6.1 wrapper for the underlying {@code IOException: Broken pipe}
     * / Tomcat {@code ClientAbortException}). Without this handler the
     * catch-all below logs the full stack at ERROR on every disconnect,
     * flooding Railway logs and burying real failures.
     *
     * <p>Emitter cleanup already runs via {@code SseEmitter.onError} in
     * {@link com.anvicorp.api.mail.service.MailSseService#register} — the
     * server-side state is fine; this is purely a log-noise fix.</p>
     */
    @ExceptionHandler({
            org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
            org.apache.catalina.connector.ClientAbortException.class
    })
    public ResponseEntity<Void> handleClientDisconnect(Throwable ex, HttpServletRequest req) {
        String endpoint = req != null ? req.getRequestURI() : "(unknown)";
        log.debug("[F4] async client disconnect at {} — {}: {}",
                endpoint, ex.getClass().getSimpleName(), ex.getMessage());
        return null;
    }

    /**
     * F4 — catch-all for anything we didn't explicitly handle. NEVER
     * leaks the exception class, stack, or SQL to the user. Logs at
     * ERROR with full context (endpoint, method, user, the entire
     * cause chain) so the user-reported traceId is one grep away from
     * the root cause.
     *
     * <p>The {@link Throwable} variant below covers
     * {@link Error} (OOM, StackOverflow, NoClassDefFoundError, …) +
     * any non-{@link Exception} {@link Throwable} that Spring's
     * exception-resolution chain wouldn't otherwise route here. If
     * BOTH the MVC layer and {@link ApiErrorController} forwarded
     * dispatch miss it, the response degrades to tomcat's HTML — so
     * we want all three layers covered.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        return handleAnyThrowable(ex, req);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleAnyThrowable(Throwable ex, HttpServletRequest req) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        String endpoint = req != null
                ? req.getMethod() + " " + req.getRequestURI()
                : "(unknown endpoint)";
        String user = currentUserDescription();
        log.error("[F4] Unhandled throwable at {} (user={}) — {}: {} | root cause: {}",
                endpoint, user, ex.getClass().getName(), ex.getMessage(),
                rootCauseMessage(ex), ex);
        String friendly = "Something went wrong on our end — we've logged it. "
                + "Please try again, or contact support"
                + (traceId != null ? " with reference " + traceId : "")
                + ".";
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", friendly, null);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ResponseEntity<ErrorResponse> body(HttpStatus status, String code,
                                                String message,
                                                Map<String, Object> details) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        ErrorResponse er = new ErrorResponse(
                status.value(),
                message,          // legacy `error` field — back-compat for 191 call sites
                message,          // canonical `message`
                code,
                traceId,
                Instant.now(),
                details);
        return ResponseEntity.status(status).body(er);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable r = rootCause(t);
        return r.getClass().getName() + ": " + r.getMessage();
    }

    private static String currentUserDescription() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return "anonymous";
            return auth.getName() + " (" + auth.getAuthorities() + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
