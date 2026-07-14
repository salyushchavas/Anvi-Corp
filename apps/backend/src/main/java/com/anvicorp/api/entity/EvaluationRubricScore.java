package com.anvicorp.api.entity;

import com.anvicorp.api.enums.RubricCriterion;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One rubric row per {@code (evaluation, criterion)}. Stored structured so
 * the executive dashboard can average per-criterion scores without parsing
 * a JSON blob; also keeps the supervisor UI honest (one numeric input per
 * criterion rather than a freeform field).
 */
@Entity
@Table(
        name = "evaluation_rubric_scores",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_evaluation_rubric_criterion",
                columnNames = {"evaluation_id", "criterion"}
        ),
        indexes = {
                @Index(name = "idx_eval_rubric_evaluation",
                        columnList = "evaluation_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationRubricScore {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RubricCriterion criterion;

    /** 1–5. Service clamps. */
    @Column(nullable = false)
    private Integer score;

    @Column(columnDefinition = "TEXT")
    private String note;
}
