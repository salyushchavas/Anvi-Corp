package com.anvicorp.api.auth;

import com.anvicorp.api.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Access-token (short-lived JWT) generator + parser. Refresh tokens live in
 * {@link com.anvicorp.api.entity.UserSession} and are NOT JWTs.
 *
 * <h2>Claims</h2>
 * <ul>
 *   <li>{@code sub} — User UUID</li>
 *   <li>{@code email}, {@code roles} — convenience for the frontend (the filter
 *       still loads the User from the DB to authorise)</li>
 *   <li>{@code session_id} — the {@code UserSession.id} this access token belongs
 *       to. Omitted on legacy tokens issued before session management landed —
 *       the filter tolerates absence so existing JWTs keep working until they
 *       naturally expire.</li>
 * </ul>
 *
 * <h2>TTL</h2>
 * Defaults to 15 minutes ({@code ACCESS_TOKEN_TTL_MINUTES}); the legacy
 * {@code jwt.expiry-hours} property is honoured only when the new property is
 * unset, so an explicit minute override always wins.
 */
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessTtlSeconds;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${app.auth.access-token-ttl-minutes:15}") long accessTtlMinutes,
            @Value("${jwt.expiry-hours:0}") long legacyExpiryHours
    ) {
        // NEVER supply a hardcoded default here — a fallback signing key would let anyone who
        // reads this source mint valid access tokens. Boot MUST refuse when the secret is bad.
        // Split "unset/blank" from "set-but-too-short" — operators routinely confuse the two,
        // and each case wants distinct remediation guidance.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Set it to a base64-encoded 32+ byte random value "
                            + "(openssl rand -base64 48) in the deployment environment "
                            + "(Railway env vars) before starting the app."
            );
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET is set but only " + keyBytes.length + " bytes long. "
                            + "HS256 requires at least 32 bytes. Regenerate with: "
                            + "openssl rand -base64 48"
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        // Backwards-compat: if the legacy hours property is set explicitly and
        // the minutes default is in effect, honour the legacy override. Once
        // both are unset, the 15-minute default applies.
        long minutesTtl = Duration.ofMinutes(accessTtlMinutes).toSeconds();
        long hoursTtl = legacyExpiryHours > 0 ? Duration.ofHours(legacyExpiryHours).toSeconds() : 0;
        this.accessTtlSeconds = hoursTtl > 0 && accessTtlMinutes == 15
                ? hoursTtl
                : minutesTtl;
    }

    /** Generate an access token bound to a session. */
    public String generateAccessToken(User user, UUID sessionId) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(Enum::name).toList();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .claim("session_id", sessionId != null ? sessionId.toString() : null)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Legacy generator — kept for paths that issue a token without a session
     * (currently none after this change, but the signature stays so any older
     * caller doesn't break the build).
     */
    public String generateToken(User user) {
        return generateAccessToken(user, null);
    }

    public Claims parseToken(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);
        return jws.getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /** Returns the session id claim, or null if absent (legacy tokens). */
    public UUID extractSessionId(Claims claims) {
        Object raw = claims.get("session_id");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Seconds-until-expiry the issued access token will live. */
    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }
}
