package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Skeletal placeholder entity for the post-hire intern's permanent home
 * record. Per the Applicant-to-Intern Lifecycle doc, this row is created at
 * the EMPLOYEE_ID_CREATED transition and carries the long-lived
 * assignments (trainer, evaluator, reporting manager, ERM) the intern is
 * paired with.
 *
 * <p>Phase 0 creates the schema only; Phase 1 wires it into the dashboard
 * endpoint. Pure UUID references — no JPA relations yet, to keep the
 * blast radius zero until the surrounding services are rebuilt.</p>
 */
@Entity
@Table(name = "intern_lifecycles", indexes = {
        @Index(name = "idx_intern_lifecycles_employee_id", columnList = "employee_id", unique = true),
        @Index(name = "idx_intern_lifecycles_user_id", columnList = "user_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InternLifecycle {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "employee_id", nullable = false, length = 40, unique = true)
    private String employeeId;

    /**
     * Phase 3 — the User this lifecycle row belongs to. UNIQUE per user;
     * inserted once at the OFFER_SIGNED webhook moment and never reassigned.
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "active_status", nullable = false, length = 24,
            columnDefinition = "varchar(24) not null default 'PROSPECTIVE'")
    @Builder.Default
    private String activeStatus = "PROSPECTIVE";

    @Column(name = "trainer_id")
    private UUID trainerId;

    @Column(name = "evaluator_id")
    private UUID evaluatorId;

    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "erm_id")
    private UUID ermId;

    /** Phase 3 — moment OFFER_SIGNED webhook fired. */
    @Column(name = "hired_at", nullable = false)
    private Instant hiredAt;

    /** Phase 4 — set when ERM marks onboarding accepted + start date reached. */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    // ── ERM Phase 4 — denormalized for the New Hire List ────────────────────

    /** Mirrored from signed offer for fast inbox queries. */
    @Column(name = "tentative_start_date")
    private java.time.LocalDate tentativeStartDate;

    /**
     * ERM-set activation switch (Pass 2). Distinct from
     * {@link #tentativeStartDate} (which is the offer letter's
     * intention): joining_date is the committed activation switch the
     * ERM sets after onboarding docs are accepted. {@link
     * com.anvicorp.api.intern.InternActivationJob#tryActivateIfReady}
     * requires this to be non-null AND {@code <= today} before flipping
     * the lifecycle to ACTIVE_INTERN. Null means "ERM hasn't committed
     * yet — keep waiting".
     */
    @Column(name = "joining_date")
    private java.time.LocalDate joiningDate;

    /** True when trainer_id + evaluator_id + manager_id are all populated. */
    @Column(name = "reporting_structure_complete", nullable = false)
    @Builder.Default
    private Boolean reportingStructureComplete = Boolean.FALSE;

    @Column(name = "reporting_structure_completed_at")
    private Instant reportingStructureCompletedAt;

    @Column(name = "reporting_structure_completed_by_id")
    private UUID reportingStructureCompletedById;

    // ── ERM Phase 8 — simplified I-9 §2 attestation ─────────────────────────

    /** Timestamp the ERM agent checked the "I-9 Section 2 completed" box. */
    @Column(name = "i9_section2_completed_at")
    private Instant i9Section2CompletedAt;

    @Column(name = "i9_section2_completed_by_id")
    private UUID i9Section2CompletedById;

    /** Free-text description of the documents the agent examined in person. */
    @Column(name = "i9_section2_documents_described", columnDefinition = "text")
    private String i9Section2DocumentsDescribed;

    @Column(name = "i9_section2_notes", columnDefinition = "text")
    private String i9Section2Notes;

    // ── Onboarding tracker (Phase: ERM gated tracker) ───────────────────────

    /**
     * Set when the ERM clicks "Notify trainer + manager" on the onboarding
     * tracker (step 4 of the selected→active flow). Drives the tracker's
     * DONE/CURRENT state for that step. Hibernate auto-DDL adds the column
     * on next boot since {@code spring.jpa.hibernate.ddl-auto=update}.
     */
    @Column(name = "team_notified_at")
    private Instant teamNotifiedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.hiredAt == null) this.hiredAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
