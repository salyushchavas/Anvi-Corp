package com.anvicorp.api.entity;

import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.WorkAuthTrack;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 3 step 1 — the post-offer "employment" record. Created at
 * OFFER_ACCEPTED (wiring lands in step 3) so the application funnel can stop
 * at ACCEPTED and the compliance/employment phase has its own home.
 *
 * 1:1 with the accepted {@link Application} and the {@link Offer} that birthed
 * it. {@link Candidate} + {@link StaffingEntity} are denormalised to keep the
 * common queries (this candidate's active engagement, engagements at this
 * entity) one join shorter.
 *
 * Defined-only at this step: nothing reads or writes this entity yet. The
 * transition guard ({@code EngagementService.transitionTo}) lands in step 2
 * and the creation hook in step 3.
 */
@Entity
@Table(
        name = "engagements",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_engagement_application", columnNames = "application_id"),
                @UniqueConstraint(name = "uk_engagement_offer", columnNames = "offer_id")
        },
        indexes = {
                @Index(name = "idx_engagement_candidate", columnList = "candidate_id"),
                @Index(name = "idx_engagement_status", columnList = "status"),
                @Index(name = "idx_engagement_entity", columnList = "entity_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Engagement {

    // ── Identity ────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, unique = true)
    private Application application;

    /** Denormalised from {@link #application}.candidate for direct querying. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offer_id", nullable = false, unique = true)
    private Offer offer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private StaffingEntity entity;

    // ── State ───────────────────────────────────────────────────────────────

    /**
     * Track-of-record snapshot at acceptance time. Independent of
     * {@code Candidate.expectedTrack}, which the candidate can mutate after
     * the engagement starts — this field is what compliance routes against.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "track", length = 16)
    private WorkAuthTrack track;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private EngagementStatus status = EngagementStatus.PENDING_COMPLIANCE;

    // ── Dates ───────────────────────────────────────────────────────────────

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_end_date")
    private LocalDate plannedEndDate;

    @Column(name = "actual_start_date")
    private LocalDate actualStartDate;

    @Column(name = "actual_end_date")
    private LocalDate actualEndDate;

    // ── Placement ───────────────────────────────────────────────────────────

    /** Internship supervisor; nullable until onboarding assigns one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_id")
    private User supervisor;

    /**
     * Reporting Manager — second reviewer that runs the post-merge viva and
     * signs the final project completion. Distinct from {@link #supervisor}
     * (the technical reviewer) so a single engagement carries both
     * sign-offs. Nullable until Operations assigns one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporting_manager_id")
    private User reportingManager;

    @Column(length = 200)
    private String worksite;

    @Column(name = "hours_per_week")
    private Integer hoursPerWeek;

    // ── Audit ───────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private UUID createdBy;
}
