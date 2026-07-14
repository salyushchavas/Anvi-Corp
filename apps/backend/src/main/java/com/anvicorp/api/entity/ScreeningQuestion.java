package com.anvicorp.api.entity;

import com.anvicorp.api.enums.ScreeningQuestionType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The default questionnaire — a small shared pool of questions every screening
 * pulls from. Per-posting custom questions are deferred; the seeder inserts
 * the default set at boot when the table is empty.
 *
 *   - {@code orderIndex} drives display order (lowest first).
 *   - {@code choicesJson} is a JSON array of strings for SINGLE_CHOICE; null for FREE_TEXT.
 *   - {@code correctChoiceIndex} is the 0-based index into {@code choicesJson};
 *     null for FREE_TEXT (which is ungraded).
 *   - {@code points} is what a correct SINGLE_CHOICE answer awards; ignored for FREE_TEXT.
 */
@Entity
@Table(name = "screening_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningQuestion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScreeningQuestionType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "choices_json", columnDefinition = "TEXT")
    private String choicesJson;

    @Column(name = "correct_choice_index")
    private Integer correctChoiceIndex;

    @Column(nullable = false)
    private Integer points;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (points == null) points = 0;
    }
}
