package com.anvicorp.api.entity;

import com.anvicorp.api.enums.DegreeLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * B2 profile expansion — one row per degree a candidate wants to disclose
 * on their profile (e.g. Bachelor's + Master's as separate entries). Exactly
 * one row per candidate carries {@code isPrimary=true}; the EducationService
 * enforces the single-primary invariant on every write and mirrors the
 * primary row back onto the legacy candidate columns (school / degreeLevel /
 * specialization / graduationYear) so every existing reader of those
 * columns keeps working unchanged.
 */
@Entity
@Table(name = "educations",
        indexes = @Index(name = "idx_educations_candidate", columnList = "candidate_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Education {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    /** Structured degree level. Nullable so the wizard can save early. */
    @Enumerated(EnumType.STRING)
    @Column(name = "degree_level", length = 20)
    private DegreeLevel degreeLevel;

    /** Institution / school name. */
    @Column(name = "institution", length = 200)
    private String institution;

    /** Major / concentration — free text (mirrors Candidate.specialization). */
    @Column(name = "field_of_study", length = 200)
    private String fieldOfStudy;

    /** Actual or expected graduation date. */
    @Column(name = "graduation_date")
    private LocalDate graduationDate;

    /**
     * Exactly one row per candidate is the primary — its fields feed the
     * legacy Candidate columns (school / degreeLevel / specialization /
     * graduationYear) via the EducationService write path.
     */
    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private Boolean isPrimary = Boolean.FALSE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
