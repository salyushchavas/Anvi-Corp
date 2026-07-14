package com.anvicorp.api.entity;

import com.anvicorp.api.enums.EvaluationRecommendation;
import com.anvicorp.api.enums.EvaluationStatus;
import com.anvicorp.api.enums.EvaluationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Periodic intern evaluation — supervisor authors, intern reads once
 * FINALIZED. Distinct from {@link EvaluationSession} (which is a scheduled
 * meeting): this is the structured assessment with rubric scores + draft
 * lifecycle.
 *
 * <h2>Lifecycle</h2>
 * {@code DRAFT → FINALIZED}. FINALIZED is terminal — locked at the service
 * layer, no edits to fields or rubric scores once flipped.
 *
 * <h2>Rubric storage</h2>
 * Per-criterion scores live in {@link EvaluationRubricScore} keyed by
 * {@code (evaluation_id, criterion)}. Stored structured so the executive
 * dashboard can compute per-criterion averages across the program.
 *
 * <h2>HR read access</h2>
 * Read endpoints allow HR alongside the engagement's supervisor
 * and SUPER_ADMIN. HR cannot author or edit. Write paths are gated to the
 * supervisor (or SUPER_ADMIN).
 */
@Entity
@Table(
        name = "evaluations",
        indexes = {
                @Index(name = "idx_evaluation_intern_status",
                        columnList = "intern_id, status"),
                @Index(name = "idx_evaluation_engagement",
                        columnList = "engagement_id"),
                @Index(name = "idx_evaluation_evaluator",
                        columnList = "evaluator_id"),
                @Index(name = "idx_evaluation_type_status",
                        columnList = "type, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Evaluation {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intern_id", nullable = false)
    private Candidate intern;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "engagement_id", nullable = false)
    private Engagement engagement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluator_id", nullable = false)
    private User evaluator;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvaluationType type;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /** 1–5 headline. Optional in DRAFT; expected at FINALIZE time. */
    @Column(name = "overall_rating")
    private Integer overallRating;

    @Column(columnDefinition = "TEXT")
    private String strengths;

    @Column(name = "areas_for_improvement", columnDefinition = "TEXT")
    private String areasForImprovement;

    @Column(columnDefinition = "TEXT")
    private String comments;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private EvaluationRecommendation recommendation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private EvaluationStatus status = EvaluationStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
