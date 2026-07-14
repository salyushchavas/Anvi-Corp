package com.anvicorp.api.auth;

import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.UserSession;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Session + refresh-token mechanics. Owned by the auth package because every
 * mutation here is paired with a JWT issuance.
 *
 * <h2>Refresh token shape</h2>
 * 32 random bytes, base64url-encoded → ~43 chars on the wire. Hashed via
 * SHA-256 before storage. Bcrypt is intentionally NOT used: this is a
 * high-entropy server-issued token, not a user-chosen password, so the slow
 * KDF buys no defence and would balloon refresh latency on the hot path.
 *
 * <h2>Rotation</h2>
 * Every successful refresh marks the presented session as revoked
 * ({@code reason=rotated}) and inserts a new row. A leaked but unused refresh
 * token becomes useless the moment the legitimate device refreshes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionTokenService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int REFRESH_BYTES = 32;

    private final UserSessionRepository sessionRepository;
    private final AuditLogRepository auditLogRepository;

    @Value("${app.auth.refresh-token-ttl-days:14}")
    private long refreshTtlDays;

    /** Container for a freshly-issued session — the raw refresh token escapes only here. */
    public record Issued(UserSession session, String rawRefreshToken) {}

    @Transactional
    public Issued issueSession(User user, HttpServletRequest request, String createReason) {
        String raw = generateRawRefreshToken();
        String hash = hash(raw);
        Instant now = Instant.now();
        UserSession session = UserSession.builder()
                .userId(user.getId())
                .refreshTokenHash(hash)
                .userAgent(truncate(request != null ? request.getHeader("User-Agent") : null, 500))
                .ip(extractClientIp(request))
                .lastUsedAt(now)
                .expiresAt(now.plus(Duration.ofDays(refreshTtlDays)))
                .revoked(false)
                .build();
        session = sessionRepository.save(session);
        writeAudit(session.getId(), "SESSION_CREATED", user.getId(), createReason);
        return new Issued(session, raw);
    }

    /**
     * Validate + rotate. The presented token must hash to a non-revoked,
     * non-expired session belonging to the supplied user. Mismatches (any
     * reason) throw {@link AuthException} so the controller can return 401
     * with a generic body — no oracles for an attacker brute-forcing tokens.
     */
    @Transactional
    public Issued rotate(User user, String presentedRaw, HttpServletRequest request) {
        if (presentedRaw == null || presentedRaw.isBlank()) {
            throw new AuthException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Invalid refresh token");
        }
        String hash = hash(presentedRaw);
        UserSession current = sessionRepository.findByRefreshTokenHash(hash)
                .orElseThrow(() -> new AuthException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Invalid refresh token"));
        if (!current.getUserId().equals(user.getId())) {
            // Token doesn't belong to this caller — silent reject. Don't leak
            // existence by hinting at the user mismatch.
            throw new AuthException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Invalid refresh token");
        }
        Instant now = Instant.now();
        if (Boolean.TRUE.equals(current.getRevoked()) || current.getExpiresAt().isBefore(now)) {
            throw new AuthException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Refresh token expired or revoked — please sign in again.");
        }

        // Revoke the presented session (rotation) and issue a fresh one.
        current.setRevoked(true);
        current.setRevokedAt(now);
        current.setRevokedReason("rotated");
        sessionRepository.save(current);

        return issueSession(user, request, "refresh_rotation");
    }

    /** SHA-256 hex of the raw token. */
    public static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always present in a JVM; this is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String generateRawRefreshToken() {
        byte[] buf = new byte[REFRESH_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Pick the best client IP from X-Forwarded-For (first hop) or the direct
     * remote address. Truncated to 64 chars so an upstream proxy injecting a
     * huge header can't blow the column.
     */
    public static String extractClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            return truncate(first, 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private void writeAudit(UUID sessionId, String action, UUID userId, String reason) {
        try {
            AuditLog row = AuditLog.builder()
                    .entityType("UserSession")
                    .entityId(sessionId)
                    .action(action)
                    .userId(userId)
                    .afterJson(reason != null ? "{\"reason\":\"" + reason + "\"}" : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("Failed to write {} audit row (non-fatal): {}", action, e.getMessage());
        }
    }
}
