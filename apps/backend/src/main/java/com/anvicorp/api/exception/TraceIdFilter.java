package com.anvicorp.api.exception;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-request short trace id, placed in SLF4J {@link MDC} under key
 * {@code traceId} and echoed as the {@code X-Trace-Id} response
 * header. Lets a user-reported "Something went wrong" map to the
 * exact backend log line in one grep.
 *
 * <p>Runs at the highest precedence so every downstream filter +
 * controller + exception handler sees the same id. The id is short
 * (first 8 chars of a UUID) so it fits in a toast / support email
 * without dominating it. Honors an inbound {@code X-Trace-Id} header
 * if the client already supplied one, otherwise generates fresh.
 * Cleared in {@code finally} so a recycled thread can't leak the id
 * into an unrelated request.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";
    /** Request attribute key — survives the {@code /error} forward dispatch
     *  (MDC does not, because {@code OncePerRequestFilter} skips error
     *  dispatches by default). {@link com.anvicorp.api.exception.ApiErrorController}
     *  reads it so the forwarded JSON 500 carries the SAME trace id the
     *  caller already received in the response header. */
    public static final String REQUEST_ATTR = "skyzen.traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String traceId = (inbound != null && !inbound.isBlank() && inbound.length() <= 64)
                ? inbound.trim()
                : UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_KEY, traceId);
        request.setAttribute(REQUEST_ATTR, traceId);
        try {
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
