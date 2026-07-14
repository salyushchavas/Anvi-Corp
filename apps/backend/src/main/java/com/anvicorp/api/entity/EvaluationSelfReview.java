package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional intern self-review attached to an {@link Evaluation}. Surfaced
 * for I-983 types (USCIS requires the student's own assessment alongside
 * the employer's). One-per-evaluation by unique constraint.
 *
 * <p>The intern submits via {@code PUT /api/v1/evaluations/{id}/self} when
 * the evaluation's type permits self-review. Once submitted, the supervisor
 * can read it as context before finalizing.
 *
 * <p><strong>Deprecated as of Evaluator Phase 0.</strong> Per the locked
 * product design decision, the intern role no longer self-reviews; the
 * Evaluator runs the full evaluation and the intern only acknowledges.
 * I-983 forms move to the dedicated {@link I983Evaluation} entity. The
 * table is preserved (any historical rows stay queryable), but new code
 * paths must not insert into it. UI references are removed in Phase 1+.
 */
@Deprecated
@Entity
@Table(
        name = "evaluation_self_reviews",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_evaluation_self_review_evaluation",
                columnNames = "evaluation_id"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationSelfReview {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false, unique = true)
    private Evaluation evaluation;

    @Column(columnDefinition = "TEXT")
    private String reflection;

    /** 1–5 intern self-rating. Optional. */
    @Column(name = "self_overall_rating")
    private Integer selfOverallRating;

    @Column(name = "self_technical_rating")
    private Integer selfTechnicalRating;

    @Column(name = "self_growth_rating")
    private Integer selfGrowthRating;

    @Column(name = "submitted_at")
    private Instant submittedAt;
}
