package com.anvicorp.api.erm.idms;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * IDMS Phase 2 — audit-style row for every state transition on a document
 * instance (SEND, INTERN_SUBMIT, RETURN, VERIFY, FINALIZE, REVOKE,
 * SUPERSEDE). Complements the global {@code audit_logs} table by keeping
 * the per-instance history one query away for the ERM cockpit's history
 * pane.
 */
@Entity
@Table(name = "document_instance_review_logs", indexes = {
        @Index(name = "idx_di_review_logs_instance",
                columnList = "instance_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentInstanceReviewLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    /** SEND | INTERN_SUBMIT | RETURN | VERIFY | FINALIZE | REVOKE | SUPERSEDE. */
    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    /** ERM | INTERN | SUPER_ADMIN. */
    @Column(name = "actor_role", length = 16)
    private String actorRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }
}
