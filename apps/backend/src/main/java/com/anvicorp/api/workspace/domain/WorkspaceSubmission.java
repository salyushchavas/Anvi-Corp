package com.anvicorp.api.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a project workspace at submission time.
 *
 * <p>Each {@code POST /workspace/submit} call writes one row plus a frozen
 * copy of every {@link ProjectWorkspaceFile} into {@link
 * WorkspaceSubmittedFile}. Subsequent edits to the workspace files do NOT
 * touch this row or its frozen files — submissions are append-only history.</p>
 *
 * <p>This is distinct from the legacy {@code entity/ProjectSubmission} that
 * stores a free-text submission note for the older intern UI. The two
 * coexist intentionally during the cutover; once the new workspace is the
 * only submit surface, the legacy entity can be retired.</p>
 *
 * <p>The {@code (projectId, submissionNumber)} pair is unique — submission
 * numbers are per-project and monotonic.</p>
 */
@Entity
@Table(
        name = "workspace_submissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ws_submission_project_number",
                columnNames = {"project_id", "submission_number"}),
        indexes = {
                @Index(name = "idx_ws_submission_project_submitted",
                        columnList = "project_id, submitted_at"),
                @Index(name = "idx_ws_submission_outcome",
                        columnList = "review_outcome")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceSubmission {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** 1-indexed, monotonic per project. The service computes "max + 1" at insert. */
    @Column(name = "submission_number", nullable = false)
    private Integer submissionNumber;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    /** Set by the evaluator when they act; null while PENDING. */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_outcome", nullable = false, length = 16)
    @Builder.Default
    private ReviewOutcome reviewOutcome = ReviewOutcome.PENDING;

    /** Reviewer-supplied reason on RETURNED; null on APPROVED / PENDING. */
    @Column(name = "review_reason", columnDefinition = "TEXT")
    private String reviewReason;
}
