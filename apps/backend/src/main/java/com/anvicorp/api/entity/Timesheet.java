package com.anvicorp.api.entity;

import com.anvicorp.api.enums.TimesheetStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "timesheets",
        indexes = {
                @Index(name = "idx_timesheet_intern", columnList = "intern_id"),
                @Index(name = "idx_timesheet_status", columnList = "status"),
                @Index(name = "idx_timesheet_week", columnList = "week_start")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Timesheet {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intern_id", nullable = false)
    private Candidate intern;

    /**
     * Phase 3 step 8 — link to the {@link Engagement} this timesheet is for.
     * Nullable for back-compat with legacy rows + interns whose engagement
     * can't be resolved at logging time. Step-11 backfill (opt-in) handles
     * the existing rows.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engagement_id")
    private Engagement engagement;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal hours;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TimesheetStatus status = TimesheetStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Phase B2 — ERM-verify stamp. Populated when status flips DRAFT/
     *  SUBMITTED → VERIFIED by an ERM (or SUPER_ADMIN). Manager-approve
     *  later reads this to confirm the row was verified before approval. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Free-text reason when the reviewer rejects the timesheet. Null otherwise. */
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
