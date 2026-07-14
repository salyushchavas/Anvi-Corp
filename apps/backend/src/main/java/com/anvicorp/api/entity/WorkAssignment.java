package com.anvicorp.api.entity;

import com.anvicorp.api.enums.WorkAssignmentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "work_assignments",
        indexes = {
                @Index(name = "idx_work_assignment_intern", columnList = "intern_id"),
                @Index(name = "idx_work_assignment_status", columnList = "status"),
                @Index(name = "idx_work_assignment_week", columnList = "week_of")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkAssignment {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intern_id", nullable = false)
    private Candidate intern;

    /**
     * Phase 3 step 8 — link to the {@link Engagement} this assignment is for.
     * Nullable so legacy rows + interns with no resolvable engagement keep
     * working via the existing intern_id-keyed queries. Step-11 backfill
     * (opt-in) will populate legacy rows.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engagement_id")
    private Engagement engagement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false)
    private User assignedBy;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "week_of", nullable = false)
    private LocalDate weekOf;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private WorkAssignmentStatus status = WorkAssignmentStatus.ASSIGNED;

    @Column(name = "submission_text", columnDefinition = "TEXT")
    private String submissionText;

    @Column(name = "submission_link")
    private String submissionLink;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
