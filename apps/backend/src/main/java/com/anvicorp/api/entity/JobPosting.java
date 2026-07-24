package com.anvicorp.api.entity;

import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.JobPostingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "job_postings",
        indexes = {
                @Index(name = "idx_job_postings_slug", columnList = "slug", unique = true),
                @Index(name = "idx_job_postings_status", columnList = "status"),
                @Index(name = "uk_job_postings_job_id", columnList = "job_id", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobPosting {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id")
    private StaffingEntity entity;

    @Column(nullable = false, unique = true)
    private String slug;

    /**
     * Human-readable, organization-unique Job ID entered by Jobs Admin.
     * Surfaced on the candidate-facing listing + detail so candidates can
     * reference a specific posting in correspondence.
     */
    @Column(name = "job_id", length = 64, unique = true)
    private String jobId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String requirements;

    private String location;

    /**
     * Renamed per the Applicant-to-Intern Lifecycle doc (Phase 0): the column
     * was {@code employment_type}; an idempotent ALTER ... RENAME COLUMN in
     * {@link com.anvicorp.api.bootstrap.SchemaFixupRunner} migrates existing
     * deployments. Java field name kept as {@code employmentType} so the
     * blast radius across DTOs/services stays small.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    @Builder.Default
    private EmploymentType employmentType = EmploymentType.INTERNSHIP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private JobPostingStatus status = JobPostingStatus.DRAFT;

    @Column(name = "published_by_id")
    private UUID publishedById;

    private Instant publishedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
