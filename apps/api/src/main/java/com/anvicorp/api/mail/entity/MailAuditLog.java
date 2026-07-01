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
 * Append-only audit trail for mail admin actions. The {@code detail} column
 * holds a short human-readable summary and MUST NEVER contain a password
 * (plaintext lives only in the one-time create/reset response). {@code action}
 * is a plain varchar (the {@link MailAuditAction} name) — no enum CHECK
 * constraint, so new actions stay additive.
 */
@Entity
@Table(name = "mail_audit_log",
        indexes = {
                @Index(name = "idx_mail_audit_actor", columnList = "actor_account_id"),
                @Index(name = "idx_mail_audit_target", columnList = "target_id"),
                @Index(name = "idx_mail_audit_created", columnList = "created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailAuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "actor_account_id", nullable = false)
    private UUID actorAccountId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(length = 1000)
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
