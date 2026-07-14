package com.anvicorp.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Server-side gate that backs the {@code users.must_change_password} flag.
 * When the authenticated principal has the flag set, every API call except
 * a short allowlist (change-password, /me bootstraps, logout, refresh,
 * CORS preflight) returns 403 with a stable error code. The frontend
 * mirrors the gate via its auth-context redirect, but THIS filter is the
 * load-bearing safety net — a crafted client cannot bypass it.
 *
 * <p>Ordering: registered AFTER {@link JwtAuthenticationFilter} so the
 * SecurityContext is already populated when this filter inspects the
 * principal. Anonymous requests fall through to the normal authz path.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ForcePasswordChangeFilter extends OncePerRequestFilter {

    /**
     * Pre-auth paths the user MUST be able to hit even while the gate is
     * active — these are the ways they get OUT of the gate (change the
     * password, fetch their own profile, sign out, redeem a fresh token).
     * Match by exact URI prefix; wildcards aren't needed because these
     * endpoints are flat.
     */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "/api/v1/users/me/change-password",
            "/api/v1/users/me",
            "/auth/me",
            "/auth/logout",
            "/auth/refresh"
    );

    public static final String ERROR_CODE = "PASSWORD_CHANGE_REQUIRED";

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        // CORS preflight is always allowed — the browser sends it without
        // an Authorization header anyway.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof User user)) {
            chain.doFilter(request, response);
            return;
        }

        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        for (String allowed : ALLOWED_PREFIXES) {
            if (uri.equals(allowed) || uri.startsWith(allowed + "/")) {
                chain.doFilter(request, response);
                return;
            }
        }

        // Blocked. 403 + structured body so the frontend axios interceptor
        // can match on the stable code rather than parsing the message.
        log.info("[ForcePasswordChange] blocked {} {} for user {} (must_change_password=true)",
                request.getMethod(), uri, user.getEmail());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ERROR_CODE);
        body.put("message",
                "Password change required before any further action. "
                        + "POST /api/v1/users/me/change-password with your current "
                        + "(temporary) password and a new one.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
