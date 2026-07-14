package com.anvicorp.api.mail.auth;

import com.anvicorp.api.exception.ErrorResponse;
import com.anvicorp.api.exception.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * 403 handler for the mail chain. Emits the shared {@link ErrorResponse} JSON
 * contract. This is the response a pre-change ({@code MAIL_PRECHANGE}) principal
 * receives for any route other than {@code /me} + change-password, and the
 * response an authenticated-but-under-privileged principal receives for an
 * admin route.
 */
@Component
@RequiredArgsConstructor
public class MailAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String msg = "Insufficient permissions";
        ErrorResponse er = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(), msg, msg, "MAIL_FORBIDDEN",
                MDC.get(TraceIdFilter.MDC_KEY), Instant.now(), null);
        objectMapper.writeValue(response.getWriter(), er);
    }
}
