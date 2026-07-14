package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 8 — one-time intern feedback survey, paired 1:1 with
 * {@link ExitRecord}. Ratings 1-5; narratives ≥50 chars; comments ≤2000.
 * No edit after submission per product (intern uses Help to amend).
 */
@Entity
@Table(name = "exit_feedback",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_exit_feedback_record",
                columnNames = "exit_record_id"),
        indexes = {
                @Index(name = "idx_exit_feedback_intern", columnList = "intern_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExitFeedback {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "exit_record_id", nullable = false, unique = true)
    private UUID exitRecordId;

    @Column(name = "intern_id", nullable = false)
    private UUID internId;

    @Column(name = "overall_rating", nullable = false)
    private Integer overallRating;

    @Column(name = "learning_rating", nullable = false)
    private Integer learningRating;

    @Column(name = "mentorship_rating", nullable = false)
    private Integer mentorshipRating;

    @Column(name = "work_environment_rating", nullable = false)
    private Integer workEnvironmentRating;

    @Column(name = "what_went_well", nullable = false, columnDefinition = "TEXT")
    private String whatWentWell;

    @Column(name = "what_could_improve", nullable = false, columnDefinition = "TEXT")
    private String whatCouldImprove;

    @Column(name = "would_recommend", nullable = false)
    private Boolean wouldRecommend;

    @Column(name = "additional_comments", columnDefinition = "TEXT")
    private String additionalComments;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
