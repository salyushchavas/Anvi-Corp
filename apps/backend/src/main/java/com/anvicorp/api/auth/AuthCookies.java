package com.anvicorp.api.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Security Wave 2 (H) — httpOnly session cookie helper.
 *
 * <p>The access-token + refresh-token pair used to live in {@code
 * localStorage} on the frontend; XSS could exfiltrate both. This helper
 * writes them into httpOnly cookies via {@code Set-Cookie} response
 * headers so JS can no longer read (and therefore leak) the values.</p>
 *
 * <p>Attributes: {@code HttpOnly} always, {@code Secure} in production,
 * {@code SameSite=Lax} (Strict would break the cross-origin login flow
 * used by the Next.js frontend). Path scoped to {@code /} so every API
 * call carries them; the refresh cookie also carries {@code /auth/refresh}
 * as a defence-in-depth narrower scope on some deployments — kept at
 * {@code /} here to match the existing single-domain deployment target.</p>
 *
 * <p>Companion CSRF cookie ({@code XSRF-TOKEN}) is NOT httpOnly because
 * the client must read it and echo it back in the {@code X-CSRF-Token}
 * request header (double-submit pattern). That token is a random 32-byte
 * base64url string minted per session and validated by
 * {@link CsrfDoubleSubmitFilter}.</p>
 */
@Component
public class AuthCookies {

    /** Access-token cookie name. Namespaced to avoid collision with the mail app's cookies. */
    public static final String ACCESS_COOKIE = "anvi_access";
    /** Refresh-token cookie name. */
    public static final String REFRESH_COOKIE = "anvi_refresh";
    /** Non-httpOnly CSRF cookie (double-submit). */
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    /** Header the client must echo the CSRF cookie in on state-changing requests. */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    /**
     * SameSite=None is required for the cross-site deployment (Vercel
     * frontend on {@code anvicorp.com}, Railway backend on a different
     * registrable domain). {@code Lax} blocks the browser from
     * attaching this cookie to cross-site XHR/fetch — every
     * authenticated API call would 401 in production and interns /
     * ERM staff would be locked out on first navigation.
     *
     * <p>Modern browsers REQUIRE {@code Secure} on any
     * {@code SameSite=None} cookie: a Set-Cookie without both is
     * silently dropped. {@link #cookieHeader} enforces that invariant
     * unconditionally (see there) so a mis-config on
     * {@code app.security.cookies.secure} can never emit a
     * None-without-Secure header that browsers would refuse.</p>
     *
     * <p>Since {@code None} disables the browser's built-in
     * SameSite=Lax CSRF protection, the {@link CsrfDoubleSubmitFilter}
     * is now LOAD-BEARING (not belt-and-suspenders): it enforces the
     * {@link #CSRF_HEADER} echo on every mutating request.</p>
     */
    private static final String SAME_SITE = "None";

    /** Access-token cookie lifetime — mirrors the JWT expiry (default 24h). */
    private static final int ACCESS_MAX_AGE_SECONDS = 60 * 60 * 24;
    /** Refresh-token cookie lifetime — mirrors {@code REFRESH_TOKEN_TTL_DAYS} (default 14d). */
    private static final int REFRESH_MAX_AGE_SECONDS = 60 * 60 * 24 * 14;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.security.cookies.secure:true}")
    private boolean secureCookies;

    public void writeAccessCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHeader(ACCESS_COOKIE, token, ACCESS_MAX_AGE_SECONDS, true));
    }

    public void writeRefreshCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHeader(REFRESH_COOKIE, token, REFRESH_MAX_AGE_SECONDS, true));
    }

    /**
     * Emits the CSRF double-submit cookie. Non-httpOnly on purpose — the
     * client (axios interceptor) reads it and echoes the value in the
     * {@link #CSRF_HEADER} on state-changing calls.
     */
    public void writeCsrfCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHeader(CSRF_COOKIE, token, ACCESS_MAX_AGE_SECONDS, false));
    }

    /** Zero all three session cookies. Called from {@code /auth/logout}. */
    public void clearAuthCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader(ACCESS_COOKIE, "", 0, true));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader(REFRESH_COOKIE, "", 0, true));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader(CSRF_COOKIE, "", 0, false));
    }

    /** Read the access-token cookie value (used by the auth filter). */
    public static String readAccessCookie(HttpServletRequest request) {
        return readCookie(request, ACCESS_COOKIE);
    }

    /** Read the refresh-token cookie value (used by {@code /auth/refresh}). */
    public static String readRefreshCookie(HttpServletRequest request) {
        return readCookie(request, REFRESH_COOKIE);
    }

    /** Read the CSRF cookie value (used by the double-submit filter). */
    public static String readCsrfCookie(HttpServletRequest request) {
        return readCookie(request, CSRF_COOKIE);
    }

    /** Fresh 32-byte base64url CSRF token — one per new session. */
    public static String mintCsrfToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String cookieHeader(String name, String value, int maxAge, boolean httpOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value == null ? "" : value);
        sb.append("; Path=/");
        sb.append("; Max-Age=").append(maxAge);
        sb.append("; SameSite=").append(SAME_SITE);
        // SameSite=None REQUIRES Secure — every modern browser silently
        // drops a Set-Cookie header that has SameSite=None without
        // Secure (RFC 6265bis + Chrome 80+ / Firefox 69+ / Safari 13+).
        // Force Secure on unconditionally when SAME_SITE == "None" so a
        // mis-config on app.security.cookies.secure (e.g. an operator
        // flipping it off for a HTTP dev tunnel) can never produce a
        // silently-dropped auth cookie that would look like a mystery
        // lockout to users.
        boolean forceSecure = "None".equals(SAME_SITE);
        if (secureCookies || forceSecure) sb.append("; Secure");
        if (httpOnly) sb.append("; HttpOnly");
        return sb.toString();
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                String value = c.getValue();
                return (value == null || value.isEmpty()) ? null : value;
            }
        }
        return null;
    }
}
