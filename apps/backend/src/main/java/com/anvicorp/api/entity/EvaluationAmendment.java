package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 6 audit row written on every amendment of a published evaluation.
 * Captures the previous-version JSON snapshot + the actor + the reason
 * before the parent {@link InternEvaluation} fields are mutated.
 */
@Entity
@Table(name = "evaluation_amendments", indexes = {
        @Index(name = "idx_eval_amendments_eval_at",
                columnList = "evaluation_id, amended_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationAmendment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "evaluation_id", nullable = false)
    private UUID evaluationId;

    @Column(name = "amended_by_id", nullable = false)
    private UUID amendedById;

    @Column(name = "amendment_reason", nullable = false, columnDefinition = "TEXT")
    private String amendmentReason;

    @Column(name = "previous_version", nullable = false)
    private Integer previousVersion;

    @Column(name = "new_version", nullable = false)
    private Integer newVersion;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "amended_at", nullable = false)
    private Instant amendedAt;

    @PrePersist
    void onCreate() {
        if (this.amendedAt == null) this.amendedAt = Instant.now();
    }
}
