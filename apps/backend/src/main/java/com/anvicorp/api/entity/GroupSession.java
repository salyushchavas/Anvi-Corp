package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Trainer-hosted group session (Zoom) for multiple interns. Same Zoom
 * creation pattern as {@link QaSession} — one Zoom meeting per session,
 * host start_url stored on the row (host-only), join URL fanned out to
 * every {@link GroupSessionParticipant}.
 *
 * <p>Participants live on the {@code group_session_participants} table
 * (one row per invited intern). Notifications on create / cancel loop
 * over the participants and reuse
 * {@code InternNotificationService.notifyIntern}.</p>
 */
@Entity
@Table(
        name = "group_sessions",
        indexes = {
                @Index(name = "idx_group_session_trainer",
                        columnList = "trainer_id, scheduled_at"),
                @Index(name = "idx_group_session_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupSession {

    @Id
    @GeneratedValue
    private UUID id;

    /** Trainer who hosts the session. */
    @Column(name = "trainer_id", nullable = false)
    private UUID trainerId;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    /** IANA id from the locked 5-zone MEETING_ZONES list. */
    @Column(name = "meeting_timezone", nullable = false, length = 50)
    private String meetingTimezone;

    @Column(name = "duration_minutes", nullable = false)
    @Builder.Default
    private Integer durationMinutes = 60;

    // ── Zoom (created by MeetingProvider on schedule) ─────────────────────

    @Column(name = "zoom_meeting_id", length = 64)
    private String zoomMeetingId;

    @Column(name = "zoom_join_url", columnDefinition = "text")
    private String zoomJoinUrl;

    /** HOST-ONLY — never serialised to intern participants. */
    @Column(name = "zoom_start_url", columnDefinition = "text")
    private String zoomStartUrl;

    @Column(name = "zoom_password", length = 40)
    private String zoomPassword;

    /** SCHEDULED | COMPLETED | CANCELLED */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "SCHEDULED";

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "groupSession", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<GroupSessionParticipant> participants = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "SCHEDULED";
        if (durationMinutes == null) durationMinutes = 60;
    }
}
