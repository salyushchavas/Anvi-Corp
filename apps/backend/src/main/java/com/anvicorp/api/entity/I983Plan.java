package com.anvicorp.api.entity;

import com.anvicorp.api.enums.CompensationFrequency;
import com.anvicorp.api.enums.DegreeLevel;
import com.anvicorp.api.enums.DsoApprovalStatus;
import com.anvicorp.api.enums.I983Status;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "i983_plans",
        indexes = {
                @Index(name = "idx_i983_candidate_status", columnList = "candidate_id, status"),
                @Index(name = "idx_i983_status", columnList = "status"),
                @Index(name = "idx_i983_entity", columnList = "entity_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class I983Plan {

    // ── Identity & State ────────────────────────────────────────────────────

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
     * Phase 3 step 6 — link the I-983 plan to its {@link Engagement}. Nullable:
     * legacy plans pre-date Engagement and stay candidate-keyed. New plans
     * resolve the engagement from the (candidate, accepted-application) pair
     * during {@code I983Service.createPlan}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engagement_id")
    private Engagement engagement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private StaffingEntity entity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private I983Status status = I983Status.DRAFT;

    // ── Section 1 — Student Information ─────────────────────────────────────

    @Column(name = "student_last_name", length = 80)
    private String studentLastName;

    @Column(name = "student_first_name", length = 80)
    private String studentFirstName;

    @Column(name = "student_middle_name", length = 80)
    private String studentMiddleName;

    @Column(name = "sevis_id", length = 15)
    private String sevisId;

    @Column(name = "uscis_number", length = 15)
    private String uscisNumber;

    @Column(name = "student_email", length = 120)
    private String studentEmail;

    @Column(name = "degree_awarded", length = 200)
    private String degreeAwarded;

    @Enumerated(EnumType.STRING)
    @Column(name = "degree_level")
    private DegreeLevel degreeLevel;

    @Column(name = "university_name", length = 200)
    private String universityName;

    @Column(name = "university_cip_code", length = 10)
    private String universityCipCode;

    @Column(name = "date_of_degree_award")
    private LocalDate dateOfDegreeAward;

    @Column(name = "opt_start_date")
    private LocalDate optStartDate;

    @Column(name = "opt_end_date")
    private LocalDate optEndDate;

    // ── Section 2 — Employer Information ────────────────────────────────────

    @Column(name = "employer_name", length = 200)
    private String employerName;

    @Column(name = "employer_ein", length = 15)
    private String employerEin;

    @Column(name = "employer_address", columnDefinition = "TEXT")
    private String employerAddress;

    @Column(name = "employer_website", length = 200)
    private String employerWebsite;

    @Column(name = "employer_naics_code", length = 10)
    private String employerNaicsCode;

    @Column(name = "employer_number_of_full_time_employees")
    private Integer employerNumberOfFullTimeEmployees;

    @Column(name = "employer_official_name", length = 160)
    private String employerOfficialName;

    @Column(name = "employer_official_title", length = 120)
    private String employerOfficialTitle;

    @Column(name = "employer_official_email", length = 120)
    private String employerOfficialEmail;

    @Column(name = "employer_official_phone", length = 30)
    private String employerOfficialPhone;

    // ── Section 3 — Training Program ────────────────────────────────────────

    @Column(name = "job_title", length = 200)
    private String jobTitle;

    @Column(name = "training_start_date")
    private LocalDate trainingStartDate;

    @Column(name = "training_end_date")
    private LocalDate trainingEndDate;

    @Column(name = "hours_per_week")
    private Integer hoursPerWeek;

    @Column(name = "compensation_amount", precision = 10, scale = 2)
    private BigDecimal compensationAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_frequency")
    private CompensationFrequency compensationFrequency;

    @Column(name = "compensation_currency", length = 3)
    private String compensationCurrency;

    @Column(name = "supervisor_name", length = 160)
    private String supervisorName;

    @Column(name = "supervisor_title", length = 120)
    private String supervisorTitle;

    @Column(name = "supervisor_email", length = 120)
    private String supervisorEmail;

    @Column(name = "supervisor_phone", length = 30)
    private String supervisorPhone;

    // ── Section 4 — Training Program Narrative ──────────────────────────────

    @Column(name = "training_program_description", columnDefinition = "TEXT")
    private String trainingProgramDescription;

    @Column(name = "how_training_relates_to_degree", columnDefinition = "TEXT")
    private String howTrainingRelatesToDegree;

    @Column(name = "training_goals_and_objectives", columnDefinition = "TEXT")
    private String trainingGoalsAndObjectives;

    @Column(name = "performance_evaluation_method", columnDefinition = "TEXT")
    private String performanceEvaluationMethod;

    @Column(name = "reporting_requirements", columnDefinition = "TEXT")
    private String reportingRequirements;

    @Column(name = "skills_knowledge_learned", columnDefinition = "TEXT")
    private String skillsKnowledgeLearned;

    @Column(name = "resources_equipment_materials", columnDefinition = "TEXT")
    private String resourcesEquipmentMaterials;

    @Column(name = "supervisor_commitments", columnDefinition = "TEXT")
    private String supervisorCommitments;

    // ── Signatures ──────────────────────────────────────────────────────────

    @Column(name = "employer_signed_at")
    private Instant employerSignedAt;

    @Column(name = "employer_signed_by_user_id")
    private UUID employerSignedByUserId;

    @Column(name = "employer_signed_name", length = 160)
    private String employerSignedName;

    @Column(name = "student_signed_at")
    private Instant studentSignedAt;

    @Column(name = "student_signed_name", length = 160)
    private String studentSignedName;

    // ── DSO Submission Tracking ─────────────────────────────────────────────

    @Column(name = "dso_submitted_at")
    private Instant dsoSubmittedAt;

    @Column(name = "dso_submitted_by_user_id")
    private UUID dsoSubmittedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dso_approval_status", nullable = false)
    @Builder.Default
    private DsoApprovalStatus dsoApprovalStatus = DsoApprovalStatus.NOT_SUBMITTED;

    @Column(name = "dso_approval_notes", columnDefinition = "TEXT")
    private String dsoApprovalNotes;

    @Column(name = "dso_responded_at")
    private Instant dsoRespondedAt;

    // ── Audit ───────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
