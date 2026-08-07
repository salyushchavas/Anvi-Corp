package com.anvicorp.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Security Wave 2 (H) — double-submit CSRF protection.
 *
 * <p>Cookies auto-send on every request, so a malicious cross-origin form
 * post now carries the {@code anvi_access} JWT along with it. The
 * double-submit pattern closes that gap: the browser client reads the
 * non-httpOnly {@link AuthCookies#CSRF_COOKIE} cookie and echoes its value
 * in the {@link AuthCookies#CSRF_HEADER} request header on every
 * state-changing call. Since a cross-origin attacker cannot read the
 * cookie value (SameOrigin policy on {@code document.cookie}), the header
 * comparison fails and the request is rejected with 403.</p>
 *
 * <h3>Scope</h3>
 * The filter only enforces on {@code POST/PUT/PATCH/DELETE}. GET/HEAD/OPTIONS
 * are treated as safe by convention (they must not mutate state). Auth
 * endpoints that MINT the cookie (login/register/refresh/logout,
 * forgot/reset-password, activate, verify-email, resend-verification) are
 * exempted — they don't need the header on the very request that ships
 * the cookie.
 *
 * <h3>Mint</h3>
 * If a GET arrives with no CSRF cookie, this filter mints one on the way
 * out (so the SPA's first page load always seeds a value the axios client
 * can read on subsequent state-changing calls). Login also mints a fresh
 * token so a rotated session gets a rotated CSRF secret.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(20)
public class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

    /** State-changing methods that require the header check. */
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * Auth endpoints that don't require a matching header — either because
     * they're the ones minting the cookie (login/register/activate) or
     * because they carry their own single-use token in the request body
     * (verify-email, reset-password, forgot-password, refresh, logout).
     * Note {@code /auth/refresh} is exempt because the client may not have
     * a stable CSRF cookie at rotation time; the refresh token itself
     * (httpOnly cookie or body) is the security-relevant credential.
     */
    private static final List<String> EXEMPT_PREFIXES = List.of(
            "/auth/register",
            "/auth/login",
            "/auth/logout",
            "/auth/refresh",
            "/auth/forgot-password",
            "/auth/reset-password",
            "/auth/verify-email",
            "/auth/resend-verification",
            "/auth/activate"
    );

    /** Public GET paths that never need auth OR CSRF — the health probe + static jobs listing. */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/health",
            "/error"
    );

    private final AuthCookies authCookies;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getServletPath();

        // Seed the CSRF cookie on any GET / safe request from a client
        // that doesn't yet carry one. Doing it here (rather than only at
        // login) means an anonymous visitor lands with a CSRF cookie
        // already primed — the first authenticated call after login can
        // then double-submit immediately.
        if (!UNSAFE_METHODS.contains(method)
                && AuthCookies.readCsrfCookie(request) == null
                && !PUBLIC_PATHS.contains(path)) {
            authCookies.writeCsrfCookie(response, AuthCookies.mintCsrfToken());
        }

        if (!UNSAFE_METHODS.contains(method)) {
            chain.doFilter(request, response);
            return;
        }
        // Auth endpoints exempted — they mint or accept the cookie.
        for (String p : EXEMPT_PREFIXES) {
            if (path.startsWith(p)) {
                chain.doFilter(request, response);
                return;
            }
        }

        // Server-to-server clients that use the Authorization header
        // instead of the session cookie skip CSRF entirely — they are not
        // subject to the browser same-origin threat model. When present,
        // the header is validated for shape by JwtAuthenticationFilter.
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")
                && AuthCookies.readAccessCookie(request) == null) {
            chain.doFilter(request, response);
            return;
        }

        String cookie = AuthCookies.readCsrfCookie(request);
        String header = request.getHeader(AuthCookies.CSRF_HEADER);
        if (cookie == null || header == null || !cookie.equals(header)) {
            writeCsrfRejection(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void writeCsrfRejection(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"status\":403,"
                        + "\"error\":\"CSRF token missing or mismatched\","
                        + "\"message\":\"Session security check failed. Refresh the page and retry.\","
                        + "\"code\":\"CSRF_FAILED\"}");
    }
}
