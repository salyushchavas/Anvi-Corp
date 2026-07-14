package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-screening, per-question answer. For SINGLE_CHOICE questions
 * {@code choiceIndex} is set and {@code awardedPoints} is either 0 or the
 * question's points value. For FREE_TEXT, {@code freeText} is set and
 * {@code awardedPoints} is always 0 (informational).
 *
 * Unique on (screening_id, question_id) to prevent double-submission per question.
 */
@Entity
@Table(name = "screening_answers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"screening_id", "question_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningAnswer {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screening_id", nullable = false)
    private Screening screening;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private ScreeningQuestion question;

    @Column(name = "choice_index")
    private Integer choiceIndex;

    @Column(name = "free_text", columnDefinition = "TEXT")
    private String freeText;

    @Column(name = "awarded_points", nullable = false)
    private Integer awardedPoints;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (awardedPoints == null) awardedPoints = 0;
    }
}
