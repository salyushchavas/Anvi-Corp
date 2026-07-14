package com.anvicorp.api.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * F4 — the LAST line of defense in the error contract.
 *
 * <p>When something escapes {@link GlobalExceptionHandler} entirely
 * (response already committed before the advice could write, exception
 * thrown from a filter / async dispatch / streaming endpoint, an
 * {@link Error} thrown rather than an {@link Exception}, or just a
 * straight 404/405 with no handler match), Spring Boot forwards the
 * request to {@code /error}. The default {@code BasicErrorController}
 * renders Spring's "Whitelabel" HTML page (or tomcat's bare HTML page
 * if Whitelabel is disabled) — both leak internals + break the F4 JSON
 * contract.</p>
 *
 * <p>This controller replaces {@code BasicErrorController} entirely
 * (Spring Boot auto-config bows out when an {@link ErrorController}
 * bean is present). Every forwarded error renders the same
 * {@link ErrorResponse} shape the per-type handlers emit, with the
 * trace id pulled from the request attribute that {@link TraceIdFilter}
 * stashes BEFORE the chain runs (MDC is unsafe here — the
 * {@code OncePerRequestFilter} skips error dispatches by default, so
 * the MDC entry was cleared in the original dispatch's {@code finally}).</p>
 */
@RestController
@Slf4j
public class ApiErrorController implements ErrorController {

    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest req) {
        Integer statusAttr = (Integer) req.getAttribute(
                RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = statusAttr != null
                ? HttpStatus.resolve(statusAttr) : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;

        Throwable ex = (Throwable) req.getAttribute(
                RequestDispatcher.ERROR_EXCEPTION);
        Object pathAttr = req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = pathAttr != null ? pathAttr.toString() : req.getRequestURI();

        // Trace id: prefer the request attribute TraceIdFilter stashed
        // (survives forward); fall back to MDC, then to a synthetic so
        // the response is never trace-less.
        String traceId = (String) req.getAttribute(TraceIdFilter.REQUEST_ATTR);
        if (traceId == null) traceId = MDC.get(TraceIdFilter.MDC_KEY);

        if (status.is5xxServerError() && ex != null) {
            log.error("[F4] Forwarded /error 5xx at {} traceId={} — {}: {}",
                    path, traceId,
                    ex.getClass().getName(), ex.getMessage(), ex);
        } else if (status.is5xxServerError()) {
            log.error("[F4] Forwarded /error 5xx at {} traceId={} "
                            + "(no exception attribute — likely a manual sendError)",
                    path, traceId);
        } else if (ex != null) {
            log.warn("[F4] Forwarded /error {} at {} traceId={} — {}: {}",
                    status.value(), path, traceId,
                    ex.getClass().getName(), ex.getMessage());
        }

        String message;
        String code;
        if (status.is5xxServerError()) {
            message = "Something went wrong on our end — we've logged it. "
                    + "Please try again, or contact support"
                    + (traceId != null ? " with reference " + traceId : "")
                    + ".";
            code = "INTERNAL_ERROR";
        } else if (status == HttpStatus.NOT_FOUND) {
            message = "Not Found";
            code = null;
        } else if (status == HttpStatus.FORBIDDEN) {
            message = "Access denied";
            code = null;
        } else if (status == HttpStatus.UNAUTHORIZED) {
            message = "Authentication required";
            code = null;
        } else {
            message = status.getReasonPhrase();
            code = null;
        }

        ErrorResponse er = new ErrorResponse(
                status.value(),
                message,
                message,
                code,
                traceId,
                Instant.now(),
                null);
        return ResponseEntity.status(status).body(er);
    }
}
