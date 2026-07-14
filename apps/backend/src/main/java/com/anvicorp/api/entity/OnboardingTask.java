package com.anvicorp.api.entity;

import com.anvicorp.api.enums.OnboardingCategory;
import com.anvicorp.api.enums.OnboardingTaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "onboarding_tasks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_onboarding_candidate_taskkey_offer",
                columnNames = {"candidate_id", "task_key", "offer_id"}
        ),
        indexes = {
                @Index(name = "idx_onboarding_candidate_sort", columnList = "candidate_id, sort_order"),
                @Index(name = "idx_onboarding_status_due", columnList = "status, due_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingTask {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id")
    private Offer offer;

    /**
     * Phase 3 step 8 — link to the {@link Engagement} this task belongs to.
     * Nullable: existing rows pre-date Engagement and remain candidate-keyed
     * for back-compat; the step-11 backfill (opt-in) will populate them.
     * Does NOT participate in the existing
     * {@code (candidate_id, task_key, offer_id)} uniqueness — that key is
     * preserved verbatim so duplicate-task prevention keeps working.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engagement_id")
    private Engagement engagement;

    @Column(name = "task_key", nullable = false, length = 50)
    private String taskKey;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OnboardingCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OnboardingTaskStatus status = OnboardingTaskStatus.PENDING;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "link_url", length = 300)
    private String linkUrl;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
