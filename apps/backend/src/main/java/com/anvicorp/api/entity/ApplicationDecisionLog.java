package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 2 — immutable history of every ERM decision (and the intern
 * provide-info closure) against an Application. One row per decision; the
 * applicant-visible message that was rendered + sent is captured here so
 * the timeline always shows exactly what the applicant saw.
 */
@Entity
@Table(name = "application_decision_logs", indexes = {
        @Index(name = "idx_app_decision_logs_app_at",
                columnList = "application_id, decided_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationDecisionLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "decided_by_id", nullable = false)
    private UUID decidedById;

    /** SHORTLIST | HOLD | REQUEST_INFO | REJECT | RESUME_FROM_HOLD | INFO_PROVIDED | WITHDRAWN. */
    @Column(name = "decision", nullable = false, length = 30)
    private String decision;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    /** ERM-only free text. NEVER returned to INTERN/MANAGER DTOs. */
    @Column(name = "reason_text", columnDefinition = "TEXT")
    private String reasonText;

    @Column(name = "previous_stage", nullable = false, length = 40)
    private String previousStage;

    @Column(name = "new_stage", nullable = false, length = 40)
    private String newStage;

    /** The rendered template body actually sent to the applicant; null for SHORTLIST / RESUME / INFO_PROVIDED. */
    @Column(name = "applicant_visible_message", columnDefinition = "TEXT")
    private String applicantVisibleMessage;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
