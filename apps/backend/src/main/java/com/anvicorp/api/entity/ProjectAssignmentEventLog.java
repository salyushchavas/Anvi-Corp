package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Trainer Phase 0 — immutable per-event audit history for project
 * assignments. Doc §11 acceptance criteria require "every file upload,
 * meeting status, feedback decision, status change is audit logged" —
 * this is the project-side equivalent of OfferEventLog,
 * InterviewEventLog, OnboardingReviewLog. Event types cover the full
 * lifecycle: CREATED, PUBLISHED, BACKDATE_AUTHORIZED, FILE_ATTACHED,
 * DUE_DATE_CHANGED, TEMPLATE_INSTANTIATED, REASSIGNED, CANCELLED,
 * SUBMITTED, REVIEWED, COMPLETED, REVISION_REQUESTED, ESCALATED,
 * FEEDBACK_PUBLISHED.
 *
 * <p>{@code comments} is free-text (no ReasonCode taxonomy — doc §9
 * uses free-text everywhere on the Trainer side).</p>
 */
@Entity
@Table(name = "project_assignment_event_logs", indexes = {
        @Index(name = "idx_project_assignment_event_logs_project",
                columnList = "project_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectAssignmentEventLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
