package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Password-reset code. Holds a short 6-digit numeric code (same shape as the
 * registration verification code — see {@code User.emailVerificationCode})
 * that the intern types on the reset wizard. Codes are scoped per-user and
 * short-lived (~15 min), so the {@code token} column is intentionally NOT
 * unique — different users can hold the same 6-digit value.
 */
@Entity
@Table(name = "password_reset_tokens", indexes = {
        @Index(name = "idx_prt_user", columnList = "user_id"),
        @Index(name = "idx_prt_user_token", columnList = "user_id, token")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean used = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
