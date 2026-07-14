package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 3 — immutable history of every interview event. One row per
 * CREATED / RESCHEDULED / INTERVIEWER_CHANGED / COMPLETED / CANCELLED /
 * NOTES_UPDATED operation. Doc §6 audit requirement.
 */
@Entity
@Table(name = "interview_event_logs", indexes = {
        @Index(name = "idx_interview_event_logs_interview_at",
                columnList = "interview_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewEventLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "interview_id", nullable = false)
    private UUID interviewId;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    /** CREATED | RESCHEDULED | INTERVIEWER_CHANGED | COMPLETED | CANCELLED | NOTES_UPDATED. */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    /** ERM-only — never serialised to INTERN. */
    @Column(name = "reason_text", columnDefinition = "TEXT")
    private String reasonText;

    /** Event-specific JSON: old vs new scheduled_for, interviewer ids, etc. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
