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
