package com.anvicorp.api.entity;

import com.anvicorp.api.enums.QaSessionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Reporting Manager's Q&amp;A (viva) session on a {@link Project}.
 *
 * <p>A session is created when the RM schedules the viva (project flips
 * {@code TECH_APPROVED → PENDING_VIVA}). During the viva the RM captures the
 * questions they asked and the intern's responses. Sign-off delegates to
 * {@link com.anvicorp.api.service.ProjectWorkflowService#completeAfterViva}
 * to flip the project to {@code COMPLETED}. Returning the work delegates to
 * {@link com.anvicorp.api.service.ProjectWorkflowService#returnForRevisions}
 * to drop the project back to {@code IN_PROGRESS}.</p>
 *
 * <p>Multiple sessions per project are allowed (returns + reschedules), so
 * there is no unique constraint on {@code project_id}.</p>
 */
@Entity
@Table(
        name = "qa_sessions",
        indexes = {
                @Index(name = "idx_qa_session_project", columnList = "project_id"),
                @Index(name = "idx_qa_session_status", columnList = "status"),
                @Index(name = "idx_qa_session_rm", columnList = "scheduled_by"),
                @Index(name = "idx_qa_session_zoom_meeting", columnList = "zoom_meeting_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QaSession {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "meeting_link", length = 1024)
    private String meetingLink;

    // ── Live session (Zoom) ───────────────────────────────────────────────
    // Mirror the KT / weekly / doubt-session pattern: schedule() asks the
    // MeetingProvider for a meeting; the host start_url is short-lived
    // (~2h zak) so the evaluator's "Start Meeting (Host)" card refetches
    // it via /api/v1/meetings/{id}/host-start on every render.

    @Column(name = "zoom_meeting_id", length = 64)
    private String zoomMeetingId;

    @Column(name = "zoom_join_url", columnDefinition = "text")
    private String zoomJoinUrl;

    /** HOST-ONLY — never serialised to the intern. */
    @Column(name = "zoom_start_url", columnDefinition = "text")
    private String zoomStartUrl;

    @Column(name = "zoom_password", length = 40)
    private String zoomPassword;

    @Column(name = "session_duration_minutes")
    private Integer sessionDurationMinutes;

    @Column(name = "session_timezone", length = 50)
    private String sessionTimezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private QaSessionStatus status = QaSessionStatus.SCHEDULED;

    /** Free-text — one question per line in the UI. */
    @Column(name = "questions_asked", columnDefinition = "TEXT")
    private String questionsAsked;

    /** Free-text — intern's responses captured during the session. */
    @Column(name = "intern_responses", columnDefinition = "TEXT")
    private String internResponses;

    /** Optional 0–10 sign-off mark. Null until COMPLETED. */
    @Column(name = "marks")
    private Integer marks;

    /** Optional sign-off remarks. */
    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    /** RM who scheduled the session (drives RM-scope queries on the dashboard). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scheduled_by")
    private User scheduledBy;

    /** RM who signed off / returned the session — usually same as scheduledBy. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conducted_by")
    private User conductedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    /** Reason captured when the RM returns the project. */
    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
