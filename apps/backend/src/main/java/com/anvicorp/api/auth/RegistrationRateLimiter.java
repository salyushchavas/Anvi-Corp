package com.anvicorp.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory rate limiter for the public /auth endpoints.
 * Zero external deps (no bucket4j / resilience4j) — a per-key sliding
 * window that counts timestamps in an {@link ArrayDeque} and prunes
 * entries older than the window.
 *
 * <p>Bot-signup mitigation: the /auth/register endpoint was fully
 * unthrottled — a scripted client could POST thousands of unverified
 * accounts per minute. This limiter caps three signals separately:</p>
 * <ul>
 *   <li>Register per IP — 5 attempts per 10 min.</li>
 *   <li>Verification-code resend per email — 3 per hour.</li>
 *   <li>Verification-code resend per IP — 20 per hour (deters an
 *       attacker rotating emails from one IP).</li>
 * </ul>
 *
 * <p>Not distributed — behind a single Railway service this is fine;
 * behind a load-balanced deployment we'd move to Redis or a token
 * bucket in a shared cache. Every hit also increments the log line so
 * a burst is visible in the deploy log.</p>
 *
 * <p>Client IP resolution mirrors
 * {@link com.anvicorp.api.auth.SessionTokenService#extractClientIp}
 * verbatim (X-Forwarded-For first hop, fall back to remote address)
 * so both surfaces see the same "who".</p>
 */
@Component
@Slf4j
public class RegistrationRateLimiter {

    /** POST /auth/register — 5 per IP per 10 minutes. */
    public static final int REGISTER_LIMIT = 5;
    public static final Duration REGISTER_WINDOW = Duration.ofMinutes(10);

    /** POST /auth/resend-verification — 3 per email per hour. */
    public static final int RESEND_PER_EMAIL_LIMIT = 3;
    public static final Duration RESEND_PER_EMAIL_WINDOW = Duration.ofHours(1);

    /** POST /auth/resend-verification — 20 per IP per hour (defense
     *  against email-rotation attacks). */
    public static final int RESEND_PER_IP_LIMIT = 20;
    public static final Duration RESEND_PER_IP_WINDOW = Duration.ofHours(1);

    // ── Security Wave 1 — extended limits ──────────────────────────────────

    /** POST /auth/login — 10 attempts per email per 15 min. Allows a
     *  fumbling user 10 shots; combined with the per-account lockout at 5
     *  (see AuthService.login) the practical ceiling is much lower. */
    public static final int LOGIN_PER_EMAIL_LIMIT = 10;
    public static final Duration LOGIN_PER_EMAIL_WINDOW = Duration.ofMinutes(15);

    /** POST /auth/login — 30 attempts per IP per 15 min. Kills credential
     *  stuffing per IP: 30/15min = 120/hr, orders of magnitude below what
     *  a stuffing tool needs to be useful. */
    public static final int LOGIN_PER_IP_LIMIT = 30;
    public static final Duration LOGIN_PER_IP_WINDOW = Duration.ofMinutes(15);

    /** POST /auth/forgot-password — 3 per email per hour. Rare legit use;
     *  prevents mail bomb against a specific inbox. */
    public static final int FORGOT_PER_EMAIL_LIMIT = 3;
    public static final Duration FORGOT_PER_EMAIL_WINDOW = Duration.ofHours(1);

    /** POST /auth/forgot-password — 10 per IP per hour. */
    public static final int FORGOT_PER_IP_LIMIT = 10;
    public static final Duration FORGOT_PER_IP_WINDOW = Duration.ofHours(1);

    /** POST /auth/reset-password — 20 per email per 15 min. The 6-digit
     *  code brute-force surface; the primary defense is the per-account
     *  attempt cap enforced elsewhere (D). This rate limit is belt-and-
     *  suspenders against distributed attacks. */
    public static final int RESET_PER_EMAIL_LIMIT = 20;
    public static final Duration RESET_PER_EMAIL_WINDOW = Duration.ofMinutes(15);

    /** POST /auth/reset-password — 30 per IP per 15 min. */
    public static final int RESET_PER_IP_LIMIT = 30;
    public static final Duration RESET_PER_IP_WINDOW = Duration.ofMinutes(15);

    /** POST /auth/verify-email — 20 per email per 15 min. Same rationale
     *  as reset. */
    public static final int VERIFY_PER_EMAIL_LIMIT = 20;
    public static final Duration VERIFY_PER_EMAIL_WINDOW = Duration.ofMinutes(15);

    /** POST /auth/verify-email — 30 per IP per 15 min. */
    public static final int VERIFY_PER_IP_LIMIT = 30;
    public static final Duration VERIFY_PER_IP_WINDOW = Duration.ofMinutes(15);

    /** Public unauth apply endpoint — 3 per IP per hour. Humans apply a
     *  few times; bots want thousands. Currently unused (all apply endpoints
     *  are gated on the INTERN role); kept for the day a truly public apply
     *  surface ships. */
    public static final int PUBLIC_APPLY_PER_IP_LIMIT = 3;
    public static final Duration PUBLIC_APPLY_PER_IP_WINDOW = Duration.ofHours(1);

    // ── Security Wave 3 — authenticated per-user limits ─────────────────────
    // Applied to expensive / abuse-prone endpoints where an authenticated
    // account could otherwise pull the system down. Keyed by userId (from
    // @AuthenticationPrincipal.getId().toString()) so each user gets their
    // own budget; different users don't share buckets.

    /** File upload endpoints (resume / documents / attachments) — 10 per
     *  user per minute. Legitimate upload flows do 1-2 files per action;
     *  a rate over 10/min is either a rerun script or an abuse burst. */
    public static final int UPLOAD_PER_USER_LIMIT = 10;
    public static final Duration UPLOAD_PER_USER_WINDOW = Duration.ofMinutes(1);

    /** Password-change endpoint — 3 per user per hour. Password rotation
     *  is a rare event; a burst is either a shell-history replay or a
     *  compromised session trying to change credentials before the real
     *  user notices. */
    public static final int PASSWORD_CHANGE_PER_USER_LIMIT = 3;
    public static final Duration PASSWORD_CHANGE_PER_USER_WINDOW = Duration.ofHours(1);

    /** Notification-trigger endpoints an authenticated user can fire
     *  (resend invite, resend acknowledgment, etc.) — 5 per user per
     *  hour. Prevents an authenticated account from turning a shared
     *  inbox into a mail-bomb target. */
    public static final int NOTIFY_PER_USER_LIMIT = 5;
    public static final Duration NOTIFY_PER_USER_WINDOW = Duration.ofHours(1);

    /** Bulk-export / ZIP endpoints — 5 per user per hour. Bulk exports
     *  are expensive server-side; the cap prevents an accidental (or
     *  intentional) refresh-loop from spawning tens of concurrent zip
     *  builds. */
    public static final int EXPORT_PER_USER_LIMIT = 5;
    public static final Duration EXPORT_PER_USER_WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    /** Register throttle — throws if the IP has exceeded its budget. */
    public void enforceRegister(HttpServletRequest request) {
        String ip = extractIp(request);
        enforce("reg:" + ip, REGISTER_LIMIT, REGISTER_WINDOW,
                "Too many registration attempts from your network. "
                        + "Wait a few minutes and try again.");
    }

    /** Resend-verification throttle by email + by IP. */
    public void enforceResendVerification(String email, HttpServletRequest request) {
        if (email != null && !email.isBlank()) {
            enforce("resend:email:" + email.trim().toLowerCase(),
                    RESEND_PER_EMAIL_LIMIT, RESEND_PER_EMAIL_WINDOW,
                    "Too many verification codes requested for this email. "
                            + "Try again later.");
        }
        String ip = extractIp(request);
        enforce("resend:ip:" + ip,
                RESEND_PER_IP_LIMIT, RESEND_PER_IP_WINDOW,
                "Too many verification requests from your network. "
                        + "Try again later.");
    }

    /**
     * Login throttle — per email AND per IP. The per-account BCrypt
     * lockout in {@link AuthService#login} is the primary brake; this
     * limiter is defense in depth against credential-stuffing bursts.
     */
    public void enforceLogin(String email, HttpServletRequest request) {
        if (email != null && !email.isBlank()) {
            enforce("login:email:" + email.trim().toLowerCase(),
                    LOGIN_PER_EMAIL_LIMIT, LOGIN_PER_EMAIL_WINDOW,
                    "Too many login attempts for this account. "
                            + "Wait a few minutes and try again.");
        }
        String ip = extractIp(request);
        enforce("login:ip:" + ip,
                LOGIN_PER_IP_LIMIT, LOGIN_PER_IP_WINDOW,
                "Too many login attempts from your network. "
                        + "Wait a few minutes and try again.");
    }

    /**
     * Forgot-password throttle — mail-bomb protection. Legit users
     * request a reset rarely; a tight cap here is safe.
     */
    public void enforceForgotPassword(String email, HttpServletRequest request) {
        if (email != null && !email.isBlank()) {
            enforce("forgot:email:" + email.trim().toLowerCase(),
                    FORGOT_PER_EMAIL_LIMIT, FORGOT_PER_EMAIL_WINDOW,
                    "Too many password-reset requests for this email. "
                            + "Try again later.");
        }
        String ip = extractIp(request);
        enforce("forgot:ip:" + ip,
                FORGOT_PER_IP_LIMIT, FORGOT_PER_IP_WINDOW,
                "Too many password-reset requests from your network. "
                        + "Try again later.");
    }

    /**
     * Reset-password throttle — belt-and-suspenders around the 6-digit
     * code brute-force surface. The account-side cap is the real
     * defense; this stops the distributed variant.
     */
    public void enforceResetPassword(String email, HttpServletRequest request) {
        if (email != null && !email.isBlank()) {
            enforce("reset:email:" + email.trim().toLowerCase(),
                    RESET_PER_EMAIL_LIMIT, RESET_PER_EMAIL_WINDOW,
                    "Too many reset-code attempts for this account. "
                            + "Wait a few minutes and try again.");
        }
        String ip = extractIp(request);
        enforce("reset:ip:" + ip,
                RESET_PER_IP_LIMIT, RESET_PER_IP_WINDOW,
                "Too many reset-code attempts from your network. "
                        + "Wait a few minutes and try again.");
    }

    /**
     * Verify-email throttle — mirror of resetPassword. The per-account
     * 5-strike cap in {@link AuthService#verifyEmail} is the primary
     * defense; this stops distributed keyspace sweeps.
     */
    public void enforceVerifyEmail(String email, HttpServletRequest request) {
        if (email != null && !email.isBlank()) {
            enforce("verify:email:" + email.trim().toLowerCase(),
                    VERIFY_PER_EMAIL_LIMIT, VERIFY_PER_EMAIL_WINDOW,
                    "Too many verification attempts for this account. "
                            + "Wait a few minutes and try again.");
        }
        String ip = extractIp(request);
        enforce("verify:ip:" + ip,
                VERIFY_PER_IP_LIMIT, VERIFY_PER_IP_WINDOW,
                "Too many verification attempts from your network. "
                        + "Wait a few minutes and try again.");
    }

    /**
     * Public unauth apply endpoint throttle — 3/IP/hr. NOT wired today
     * (every apply endpoint is authenticated + INTERN-gated). Kept for
     * the day a truly public apply surface ships.
     */
    public void enforcePublicApply(HttpServletRequest request) {
        String ip = extractIp(request);
        enforce("apply:ip:" + ip,
                PUBLIC_APPLY_PER_IP_LIMIT, PUBLIC_APPLY_PER_IP_WINDOW,
                "Too many application submissions from your network. "
                        + "Try again later.");
    }

    // ── Security Wave 3 — authenticated per-user enforcement ────────────────

    /**
     * File-upload rate limit (per authenticated user). Wired into resume /
     * document / weekly-report / project-attachment upload endpoints. Keyed
     * by the caller's user id so distinct users get distinct budgets.
     */
    public void enforceAuthenticatedUpload(java.util.UUID userId) {
        if (userId == null) return; // anonymous requests skip — the auth
                                    // filter already rejected them upstream.
        enforce("upload:user:" + userId,
                UPLOAD_PER_USER_LIMIT, UPLOAD_PER_USER_WINDOW,
                "Too many uploads in a short period. "
                        + "Wait a moment before uploading again.");
    }

    /**
     * Password-change rate limit (per authenticated user). Wired into the
     * self-service change-password endpoint so a compromised session
     * cannot rapidly cycle passwords to defeat the "revoke other sessions"
     * side effect built into a successful change.
     */
    public void enforcePasswordChange(java.util.UUID userId) {
        if (userId == null) return;
        enforce("pwchange:user:" + userId,
                PASSWORD_CHANGE_PER_USER_LIMIT, PASSWORD_CHANGE_PER_USER_WINDOW,
                "Too many password change attempts. "
                        + "Wait an hour before trying again.");
    }

    /**
     * Notification-trigger rate limit (per authenticated user). Applies to
     * resend-invite / resend-acknowledgment / re-notify actions an
     * authenticated staff member can fire.
     */
    public void enforceAuthenticatedNotify(java.util.UUID userId) {
        if (userId == null) return;
        enforce("notify:user:" + userId,
                NOTIFY_PER_USER_LIMIT, NOTIFY_PER_USER_WINDOW,
                "Too many notifications requested in a short period. "
                        + "Try again later.");
    }

    /**
     * Bulk-export rate limit (per authenticated user). Wired into the
     * ERM/Manager document-gallery ZIP export + any other bulk export
     * endpoints that would be expensive to spin up in a tight loop.
     */
    public void enforceAuthenticatedExport(java.util.UUID userId) {
        if (userId == null) return;
        enforce("export:user:" + userId,
                EXPORT_PER_USER_LIMIT, EXPORT_PER_USER_WINDOW,
                "Too many bulk exports in a short period. "
                        + "Wait a moment before requesting another export.");
    }

    private void enforce(String key, int limit, Duration window, String errMsg) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> hits = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            // Prune anything outside the window first — cheap because the
            // deque stays small (bounded by the limit + a few stragglers).
            while (!hits.isEmpty() && hits.peekFirst().isBefore(cutoff)) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                Instant oldest = hits.peekFirst();
                long retryAfterSec = oldest == null ? window.getSeconds()
                        : Math.max(1L, oldest.plus(window).getEpochSecond() - now.getEpochSecond());
                log.warn("[RateLimit] throttled key={} hits={} window={} retryAfter={}s",
                        key, hits.size(), window, retryAfterSec);
                throw new RateLimitException(errMsg, retryAfterSec);
            }
            hits.addLast(now);
        }
    }

    private static String extractIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            return truncate(first, 64);
        }
        String remote = request.getRemoteAddr();
        return remote != null ? truncate(remote, 64) : "unknown";
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
