package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 8 — immutable per-event audit history for each
 * {@link DocumentTask}. Mirrors the OnboardingReviewLog pattern from
 * ERM Phase 5 (which this entity supersedes).
 *
 * <p>Event types: TEMPLATE_ASSIGNED, INTERN_DOWNLOADED, INTERN_UPLOADED,
 * ERM_VIEWED_UPLOAD, ACCEPTED, REJECTED, RESEND_REQUESTED, WAIVED,
 * REOPENED, COMMENTS_ADDED, MIGRATED_FROM_LEGACY_FORM.</p>
 */
@Entity
@Table(name = "document_task_review_logs", indexes = {
        @Index(name = "idx_document_task_review_logs_task",
                columnList = "task_id, created_at"),
        @Index(name = "idx_document_task_review_logs_actor",
                columnList = "actor_user_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentTaskReviewLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** Null = system actor (migration, scheduled job). */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "previous_status", length = 20)
    private String previousStatus;

    @Column(name = "new_status", length = 20)
    private String newStatus;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    /** ERM-only — never returned to INTERN. */
    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
