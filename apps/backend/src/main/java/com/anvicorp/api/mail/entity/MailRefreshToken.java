package com.anvicorp.api.mail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * DB-backed rotating refresh token for a mail account. The raw token is never
 * stored: only its SHA-256 hex digest. Each successful rotate revokes the
 * presented row ({@code revokedReason = "rotated"}) before issuing a new one, so
 * a replayed token fails the {@code revoked} check (single-use rotation).
 */
@Entity
@Table(name = "mail_refresh_tokens",
        indexes = {
                @Index(name = "idx_mail_refresh_account_active", columnList = "account_id, revoked"),
                @Index(name = "idx_mail_refresh_hash", columnList = "refresh_token_hash", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailRefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** SHA-256 hex of the raw refresh token. Unique — DB rejects collisions. */
    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 128)
    private String refreshTokenHash;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "ip", length = 64)
    private String ip;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = Boolean.FALSE;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** "rotated" | "user_logout" | "password_changed". */
    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;
}
