package com.anvicorp.api.auth;

import com.anvicorp.api.common.ErrorResponse;
import com.anvicorp.api.common.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * 401 entry point for the mail chain. Emits the shared {@link ErrorResponse}
 * JSON contract (with trace id). Only ever wired into the mail chain's
 * {@code exceptionHandling}.
 */
@Component
@RequiredArgsConstructor
public class MailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String msg = "Authentication required";
        ErrorResponse er = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(), msg, msg, "MAIL_UNAUTHENTICATED",
                MDC.get(TraceIdFilter.MDC_KEY), Instant.now(), null);
        objectMapper.writeValue(response.getWriter(), er);
    }
}
