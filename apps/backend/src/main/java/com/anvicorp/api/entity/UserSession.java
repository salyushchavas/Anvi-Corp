package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Revocable session row backing the short-lived-access-token + refresh-token
 * model.
 *
 * <p><b>Refresh-token hash:</b> stored hashed (SHA-256 of the raw token), never
 * raw. The raw value lives only in the response body the client receives at
 * issuance and is then opaque to the server until presented again for
 * rotation.</p>
 *
 * <p><b>Lifecycle:</b> created on login; updated on every successful refresh
 * (last_used_at + rotated hash); revoked by the user (single-session sign-out
 * or sign-out-everywhere) or implicitly on rotation. Once revoked, the row
 * stays for audit; the next refresh attempt against that hash fails and the
 * device is signed out.</p>
 */
@Entity
@Table(
        name = "user_sessions",
        indexes = {
                @Index(name = "idx_user_sessions_user_active",
                        columnList = "user_id, revoked"),
                @Index(name = "idx_user_sessions_refresh_hash",
                        columnList = "refresh_token_hash", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** SHA-256 hex of the raw refresh token. Unique — DB rejects collisions. */
    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 128)
    private String refreshTokenHash;

    /** Raw User-Agent header at issuance. Display-formatted by SessionService. */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** Best-effort client IP at issuance (proxy-aware). */
    @Column(name = "ip", length = 64)
    private String ip;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    /** Hard cutoff for the refresh token. Set at insertion based on REFRESH_TOKEN_TTL_DAYS. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = Boolean.FALSE;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Free-text reason — "user_revoked", "sign_out_everywhere", "rotated". */
    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;
}
