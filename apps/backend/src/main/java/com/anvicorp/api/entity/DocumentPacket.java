package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 8 — per-intern wrapper around a set of {@link DocumentTask}
 * rows. One active packet per {@code intern_lifecycle_id} (enforced by
 * the partial UNIQUE {@code uq_document_packets_active_per_lifecycle}
 * WHERE status&lt;&gt;'CANCELLED').
 *
 * <p>Lifecycle: {@code DRAFT → ASSIGNED → IN_PROGRESS → ALL_SUBMITTED →
 * COMPLETED}, with {@code CANCELLED} as the terminal escape hatch.
 * Status transitions live on {@code DocumentPacketService}.</p>
 */
@Entity
@Table(name = "document_packets", indexes = {
        @Index(name = "idx_document_packets_lifecycle_status",
                columnList = "intern_lifecycle_id, status"),
        @Index(name = "idx_document_packets_status_assigned",
                columnList = "status, assigned_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentPacket {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "intern_lifecycle_id", nullable = false)
    private UUID internLifecycleId;

    @Column(name = "assigned_by_id", nullable = false)
    private UUID assignedById;

    /** DRAFT | ASSIGNED | IN_PROGRESS | ALL_SUBMITTED | COMPLETED | CANCELLED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ASSIGNED";

    @Column(name = "custom_instructions", columnDefinition = "TEXT")
    private String customInstructions;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "first_submission_at")
    private Instant firstSubmissionAt;

    @Column(name = "all_submitted_at")
    private Instant allSubmittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    /**
     * Phase 1.6 — explicit intern handoff signal. Set to {@code true}
     * by {@code DocumentPacketService.submitByIntern} when the intern
     * clicks "Submit all documents to ERM". While true, the intern can
     * no longer upload or replace files on this packet; ERM owns the
     * packet for verification. Reset to {@code false} the moment ERM
     * REJECTs or RESEND_REQUESTs any single task (via
     * {@code reviewTask}), reopening the rejected doc(s) for the
     * intern to re-upload + re-submit. Orthogonal to {@code status}:
     * the existing 6-status state machine is unchanged.
     */
    @Column(name = "intern_locked", nullable = false)
    @Builder.Default
    private Boolean internLocked = Boolean.FALSE;

    /** Timestamp of the most recent intern submit. Never cleared; useful
     *  for "submitted X hours ago" copy on the ERM review surface. */
    @Column(name = "intern_submitted_at")
    private Instant internSubmittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.assignedAt == null) this.assignedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
