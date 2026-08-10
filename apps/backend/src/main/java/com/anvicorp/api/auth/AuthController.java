package com.anvicorp.api.auth;

import com.anvicorp.api.auth.dto.AuthResponse;
import com.anvicorp.api.auth.dto.ForgotPasswordRequest;
import com.anvicorp.api.auth.dto.LoginRequest;
import com.anvicorp.api.auth.dto.MeResponse;
import com.anvicorp.api.auth.dto.RefreshTokenRequest;
import com.anvicorp.api.auth.dto.RegisterRequest;
import com.anvicorp.api.auth.dto.ResendVerificationRequest;
import com.anvicorp.api.auth.dto.ResetPasswordRequest;
import com.anvicorp.api.auth.dto.VerifyEmailRequest;
import com.anvicorp.api.auth.dto.VerifyEmailResponse;
import com.anvicorp.api.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final RegistrationRateLimiter rateLimiter;
    private final AuthCookies authCookies;
    private final TurnstileVerifier turnstileVerifier;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        // Bot mitigation — four layers ordered cheap-to-expensive:
        //
        //  1. Honeypot. Any non-blank value in {@code companyWebsite} is
        //     a definitive bot signal (real users never see that field —
        //     it's off-screen with tabindex=-1). Reject early with a
        //     generic 400 so an attacker can't grep for a distinctive
        //     fingerprint. Cheapest — no HTTP call, no DB touch.
        //  2. Rate limit. Per-IP sliding window (5 per 10 min) already
        //     bounds throughput; a distributed bot rotating IPs will
        //     still hit the next two.
        //  3. Turnstile. Cloudflare-issued token verified server-side
        //     against their siteverify API. Fails closed when the
        //     master switch is on but the secret is blank / the network
        //     roundtrip fails — see {@link TurnstileVerifier}.
        //  4. Service layer email-uniqueness + password/ToS validation.
        //     The prior 3 keep this from becoming a bot-throughput floor.
        if (req.companyWebsite() != null && !req.companyWebsite().isBlank()) {
            log.warn("[BotMitigation] register honeypot tripped from ip={}",
                    SessionTokenService.extractClientIp(httpRequest));
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "Registration blocked. Please refresh and try again.");
        }
        rateLimiter.enforceRegister(httpRequest);
        boolean captchaOk = turnstileVerifier.verify(
                req.captchaToken(),
                SessionTokenService.extractClientIp(httpRequest));
        if (!captchaOk) {
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "Please complete the human-verification challenge and try again.");
        }
        AuthResponse body = authService.register(req, httpRequest);
        writeSessionCookies(httpResponse, body);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        // Per-email + per-IP throttle in front of the BCrypt cost. Bounds
        // credential-stuffing throughput even before the per-account
        // lockout counter kicks in.
        rateLimiter.enforceLogin(req.email(), httpRequest);
        AuthResponse body = authService.login(req, httpRequest);
        writeSessionCookies(httpResponse, body);
        return ResponseEntity.ok(body);
    }

    /**
     * Exchange a refresh token for a fresh access+refresh pair. The presented
     * refresh token is revoked on success (rotation); a replayed or revoked
     * token returns 401 and the device is effectively signed out.
     *
     * <p>Security Wave 2 — the refresh token is now transported in the
     * httpOnly {@code anvi_refresh} cookie the browser sends automatically.
     * The request body is optional (retained for backward compatibility
     * during rollout and for any server-to-server caller that still POSTs
     * the raw token). Cookie wins when both are supplied.</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) RefreshTokenRequest req,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        String cookieToken = AuthCookies.readRefreshCookie(httpRequest);
        String bodyToken = req == null ? null : req.refreshToken();
        String token = cookieToken != null ? cookieToken : bodyToken;
        if (token == null || token.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Refresh token missing");
        }
        AuthResponse body = authService.refresh(token, httpRequest);
        writeSessionCookies(httpResponse, body);
        return ResponseEntity.ok(body);
    }

    /**
     * Security Wave 2 — explicit logout endpoint. Clears the three
     * session cookies ({@code anvi_access}, {@code anvi_refresh},
     * {@code XSRF-TOKEN}) by re-issuing them with {@code Max-Age=0}.
     * Does not revoke the DB session row — a stolen refresh token cannot
     * be laundered through the cookie clear alone; use the session
     * management endpoints to revoke. Idempotent: safe to call while
     * unauthenticated (permits a "sign out from everywhere" UX).
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletResponse httpResponse) {
        authCookies.clearAuthCookies(httpResponse);
        return ResponseEntity.ok(Map.of("message", "Signed out"));
    }

    /**
     * Emits the httpOnly access + refresh cookies AND a fresh CSRF cookie
     * every time a session is created (login / register / refresh /
     * activate). Rotating the CSRF token on session rotation means a
     * compromised prior CSRF value stops working the moment the user
     * signs back in.
     */
    private void writeSessionCookies(HttpServletResponse response, AuthResponse body) {
        if (body == null) return;
        if (body.token() != null) {
            authCookies.writeAccessCookie(response, body.token());
        }
        if (body.refreshToken() != null) {
            authCookies.writeRefreshCookie(response, body.refreshToken());
        }
        authCookies.writeCsrfCookie(response, AuthCookies.mintCsrfToken());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req,
                                                              HttpServletRequest httpRequest) {
        // Tight per-email + per-IP cap — mail-bomb protection against a
        // specific inbox and against wide-net probing from one IP. Still
        // returns the generic "If the email is registered…" message on
        // success so account existence stays hidden.
        rateLimiter.enforceForgotPassword(req.email(), httpRequest);
        authService.forgotPassword(req);
        return ResponseEntity.ok(Map.of("message",
                "If the email is registered, a reset code has been sent"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest req,
                                                             HttpServletRequest httpRequest) {
        // The 6-digit reset code is the primary brute-force surface. This
        // rate limit is a network-side backstop; the per-token
        // invalidation happens at the service.
        rateLimiter.enforceResetPassword(req.email(), httpRequest);
        authService.resetPassword(req);
        return ResponseEntity.ok(Map.of("message", "Password reset successful"));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest req,
                                                           HttpServletRequest httpRequest) {
        // Rate-limit belt-and-suspenders around the 6-digit verification
        // code. The per-account 5-strike cap in AuthService.verifyEmail
        // is the primary defense.
        rateLimiter.enforceVerifyEmail(req.email(), httpRequest);
        return ResponseEntity.ok(authService.verifyEmail(req));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest req,
            HttpServletRequest httpRequest) {
        // Per-email + per-IP throttle on the resend surface. Prevents
        // bots from hammering the code-issue endpoint to burn through
        // codes or hunt for one that reveals whether an account exists.
        rateLimiter.enforceResendVerification(req.email(), httpRequest);
        authService.resendVerification(req);
        // Always 200 — we don't reveal whether an account exists for that email.
        return ResponseEntity.ok(Map.of("message",
                "If the account exists and is unverified, a new code has been sent"));
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(authService.me(user));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handleAuthException(AuthException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("error", ex.getMessage()));
    }

    /** Map rate-limit rejections to 429 with a Retry-After header so
     *  a well-behaved client (and any browser fetch shim) can back off
     *  automatically. Response body still carries the human message. */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, String>> handleRateLimit(RateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of("error", ex.getMessage()));
    }
}
