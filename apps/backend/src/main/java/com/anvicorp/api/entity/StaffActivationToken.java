package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One outstanding admin-invite activation token. Issued by
 * {@code AdminUserService.create} when a SUPER_ADMIN creates a staff
 * account; redeemed by {@code AuthActivationService.activate} when the
 * user opens the link and sets their first password.
 *
 * <h2>Security</h2>
 * Only the SHA-256 hex of the raw token is persisted ({@link #tokenHash}).
 * The raw value is shown ONCE — in the admin's create-response and in the
 * activation email — and never stored. A DB leak therefore cannot be
 * replayed: an attacker would need the raw bytes (~256 bits) of every
 * outstanding token to do anything useful.
 *
 * <h2>Single-use</h2>
 * {@link #usedAt} is null while the token is live. Set on successful
 * activation; subsequent redemption attempts find the row but reject
 * because {@code usedAt != null}.
 *
 * <h2>Expiry</h2>
 * 24 hours from issue (see {@code AdminUserService.ACTIVATION_TOKEN_TTL}).
 * Validation MUST check {@link #expiresAt} against {@code Instant.now()}
 * — the unique-index doesn't help when an old row's just sitting around
 * past its window.
 */
@Entity
@Table(name = "staff_activation_tokens", indexes = {
        @Index(name = "idx_staff_activation_tokens_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_staff_activation_tokens_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffActivationToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** SHA-256 hex of the raw token. The raw value is NEVER stored. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null while live; set to {@code Instant.now()} on successful redemption. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The SUPER_ADMIN who issued the invite. Nullable for forensic completeness only. */
    @Column(name = "created_by_id")
    private UUID createdById;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }
}
