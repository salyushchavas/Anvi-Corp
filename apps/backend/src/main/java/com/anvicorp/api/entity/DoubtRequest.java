package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Doubt-session feature — an active intern flags a blocker on a project;
 * their Trainer either replies in writing OR schedules a one-off Zoom
 * session (the same MeetingProvider seam KT / weekly meetings use).
 *
 * <p>Status flow: {@code PENDING → REPLIED | SESSION_SCHEDULED → RESOLVED}.
 * {@code CANCELLED} is reserved for an intern self-cancel before the
 * trainer has acted; v1 doesn't expose the action but the column accepts
 * the value.</p>
 *
 * <p>Zoom fields are stored INLINE (not a FK to {@code weekly_meetings})
 * because doubt sessions are one-off and don't share the recurrence
 * machinery. Mirrors how {@code Project.kt_zoom_*} columns live on the
 * parent row.</p>
 *
 * <p>Attachment is a FK to the existing {@code documents} vault row so
 * encryption + soft-delete + the dual-resolver come for free.</p>
 */
@Entity
@Table(name = "doubt_requests", indexes = {
        @Index(name = "idx_doubt_requests_intern", columnList = "intern_user_id, status"),
        @Index(name = "idx_doubt_requests_trainer_open",
                columnList = "trainer_user_id, status, created_at"),
        @Index(name = "idx_doubt_requests_project", columnList = "project_id, created_at"),
        @Index(name = "idx_doubt_requests_zoom_meeting", columnList = "zoom_meeting_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoubtRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "intern_user_id", nullable = false)
    private UUID internUserId;

    @Column(name = "trainer_user_id", nullable = false)
    private UUID trainerUserId;

    @Column(name = "intern_lifecycle_id", nullable = false)
    private UUID internLifecycleId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "project_assignment_id")
    private UUID projectAssignmentId;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    /** FK into {@code documents} vault. Nullable — text-only doubts allowed. */
    @Column(name = "attachment_document_id")
    private UUID attachmentDocumentId;

    @Column(name = "status", nullable = false, length = 32,
            columnDefinition = "varchar(32) not null default 'PENDING'")
    @Builder.Default
    private String status = "PENDING";

    // ── Reply (trainer async response) ────────────────────────────────────

    @Column(name = "trainer_reply", columnDefinition = "text")
    private String trainerReply;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "replied_by_id")
    private UUID repliedById;

    // ── Live session (Zoom) ───────────────────────────────────────────────

    @Column(name = "zoom_meeting_id", length = 64)
    private String zoomMeetingId;

    @Column(name = "zoom_join_url", columnDefinition = "text")
    private String zoomJoinUrl;

    /** HOST-ONLY — never serialised to the intern. */
    @Column(name = "zoom_start_url", columnDefinition = "text")
    private String zoomStartUrl;

    @Column(name = "zoom_password", length = 40)
    private String zoomPassword;

    @Column(name = "session_scheduled_for")
    private Instant sessionScheduledFor;

    @Column(name = "session_duration_minutes")
    private Integer sessionDurationMinutes;

    @Column(name = "session_timezone", length = 50)
    private String sessionTimezone;

    // ── Resolved ──────────────────────────────────────────────────────────

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_id")
    private UUID resolvedById;

    // ── Timestamps ────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp not null default now()")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
