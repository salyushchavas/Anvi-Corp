package com.anvicorp.api.mail.auth;

import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailRefreshToken;
import com.anvicorp.api.mail.exception.MailAuthException;
import com.anvicorp.api.mail.repository.MailRefreshTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
 * DB-backed rotating refresh-token service for the mail module: 32-byte
 * base64url raw token, SHA-256 hex at rest, single-use rotation (the presented
 * row is revoked before a new one is issued, so a replayed token is rejected).
 */
@Service
@RequiredArgsConstructor
public class MailSessionTokenService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int REFRESH_BYTES = 32;

    private final MailRefreshTokenRepository refreshTokenRepository;

    @Value("${app.webmail.auth.refresh-token-ttl-days:14}")
    private long refreshTtlDays;

    /** Carries the persisted row + the raw token (raw escapes ONLY here). */
    public record Issued(MailRefreshToken token, String rawRefreshToken) {
    }

    @Transactional
    public Issued issue(MailAccount account, HttpServletRequest request) {
        String raw = generateRawRefreshToken();
        Instant now = Instant.now();
        MailRefreshToken token = MailRefreshToken.builder()
                .accountId(account.getId())
                .refreshTokenHash(hash(raw))
                .userAgent(truncate(request != null ? request.getHeader("User-Agent") : null, 500))
                .ip(truncate(request != null ? request.getRemoteAddr() : null, 64))
                .lastUsedAt(now)
                .expiresAt(now.plus(Duration.ofDays(refreshTtlDays)))
                .revoked(false)
                .build();
        token = refreshTokenRepository.save(token);
        return new Issued(token, raw);
    }

    @Transactional
    public Issued rotate(MailAccount account, String presentedRaw, HttpServletRequest request) {
        MailRefreshToken current = validateAndLoad(account, presentedRaw);
        Instant now = Instant.now();
        current.setRevoked(true);
        current.setRevokedAt(now);
        current.setRevokedReason("rotated");
        refreshTokenRepository.save(current);
        return issue(account, request);
    }

    @Transactional
    public void revoke(String presentedRaw, String reason) {
        if (presentedRaw == null || presentedRaw.isBlank()) {
            return;
        }
        refreshTokenRepository.findByRefreshTokenHash(hash(presentedRaw)).ifPresent(t -> {
            if (!Boolean.TRUE.equals(t.getRevoked())) {
                t.setRevoked(true);
                t.setRevokedAt(Instant.now());
                t.setRevokedReason(reason);
                refreshTokenRepository.save(t);
            }
        });
    }

    @Transactional
    public int revokeAllForAccount(UUID accountId, String reason) {
        return refreshTokenRepository.revokeAllForAccount(accountId, Instant.now(), reason);
    }

    private MailRefreshToken validateAndLoad(MailAccount account, String presentedRaw) {
        if (presentedRaw == null || presentedRaw.isBlank()) {
            throw new MailAuthException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", "MAIL_REFRESH_INVALID");
        }
        MailRefreshToken current = refreshTokenRepository.findByRefreshTokenHash(hash(presentedRaw))
                .orElseThrow(() -> new MailAuthException(
                        HttpStatus.UNAUTHORIZED, "Invalid refresh token", "MAIL_REFRESH_INVALID"));
        if (!current.getAccountId().equals(account.getId())) {
            // Token doesn't belong to this account — silent generic reject.
            throw new MailAuthException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", "MAIL_REFRESH_INVALID");
        }
        if (Boolean.TRUE.equals(current.getRevoked()) || current.getExpiresAt().isBefore(Instant.now())) {
            throw new MailAuthException(HttpStatus.UNAUTHORIZED,
                    "Refresh token expired or revoked — please sign in again.", "MAIL_REFRESH_EXPIRED");
        }
        return current;
    }

    public static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String generateRawRefreshToken() {
        byte[] buf = new byte[REFRESH_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
