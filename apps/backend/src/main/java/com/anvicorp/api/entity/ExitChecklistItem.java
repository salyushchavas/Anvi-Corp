package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 7 — one persistent row per checklist item per ExitRecord.
 * Eight items auto-seed on exit initiation (see
 * {@code ExitChecklistItemRegistry}). System auto-flips items as
 * conditions satisfy (GitHub revoked, intern feedback submitted, no
 * outstanding timesheets/projects, final evaluation linked); ERM
 * manually toggles the rest. Manager override flips all PENDING items
 * to WAIVED with a single reason.
 */
@Entity
@Table(name = "exit_checklist_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_exit_checklist_items_record_key",
                columnNames = {"exit_record_id", "item_key"}),
        indexes = {
                @Index(name = "idx_exit_checklist_items_record_status",
                        columnList = "exit_record_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExitChecklistItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "exit_record_id", nullable = false)
    private UUID exitRecordId;

    /** FINAL_EVALUATION | OUTSTANDING_TIMESHEETS | OUTSTANDING_PROJECTS |
     *  GITHUB_REVOKED | ASSETS_RETURNED | DOCUMENTS_ARCHIVED |
     *  EXIT_FEEDBACK_SUBMITTED | FINAL_PAYROLL_CONFIRMED. */
    @Column(name = "item_key", nullable = false, length = 60)
    private String itemKey;

    /** PENDING | COMPLETE | NOT_APPLICABLE | WAIVED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by_id")
    private UUID completedById;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
