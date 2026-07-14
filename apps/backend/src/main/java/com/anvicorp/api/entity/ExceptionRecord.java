package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 6 — persistent exception detection. One row per
 * (intern, exception_type) while the underlying condition is unresolved.
 * The scheduled {@code ExceptionScanJob} UPSERTs these rows every 15 min;
 * the partial UNIQUE index ON (subject_user_id, exception_type) WHERE
 * status IN ('OPEN','ASSIGNED','IN_PROGRESS') guarantees at most one
 * active row per (intern, type) regardless of UPSERT race.
 *
 * <p>RESOLVED / DISMISSED / AUTO_RESOLVED rows are excluded from the
 * partial UNIQUE so the reopen-after-resolve flow can land a fresh
 * active row keyed by the same (intern, type) pair.</p>
 */
@Entity
@Table(name = "exception_records", indexes = {
        @Index(name = "idx_exception_records_status_severity",
                columnList = "status, severity, opened_at"),
        @Index(name = "idx_exception_records_assignee",
                columnList = "assigned_to_id, status"),
        @Index(name = "idx_exception_records_subject",
                columnList = "subject_user_id, status, exception_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExceptionRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "intern_lifecycle_id", nullable = false)
    private UUID internLifecycleId;

    @Column(name = "subject_user_id", nullable = false)
    private UUID subjectUserId;

    /** See {@link com.anvicorp.api.erm.exception.ExceptionType}. */
    @Column(name = "exception_type", nullable = false, length = 50)
    private String exceptionType;

    /** URGENT | WARN | INFO. */
    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    /** OPEN | ASSIGNED | IN_PROGRESS | RESOLVED | DISMISSED | AUTO_RESOLVED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** Refreshed every scan tick the underlying condition is still true. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "assigned_to_id")
    private UUID assignedToId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "assigned_by_id")
    private UUID assignedById;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_id")
    private UUID resolvedById;

    /** ERM-only — never returned to INTERN. */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "resolution_reason_code", length = 80)
    private String resolutionReasonCode;

    /** APPLICATION | OFFER | ONBOARDING_ITEM | PROJECT | MEETING | TIMESHEET | EVALUATION | EVERIFY | EXIT. */
    @Column(name = "subject_resource_type", length = 40)
    private String subjectResourceType;

    @Column(name = "subject_resource_id")
    private UUID subjectResourceId;

    /** Sanitised, type-specific payload (days overdue, expected date, count). NO PII. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.openedAt == null) this.openedAt = now;
        if (this.lastSeenAt == null) this.lastSeenAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
