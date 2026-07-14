package com.anvicorp.api.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Frozen-at-submission-time copy of one file in a {@link WorkspaceSubmission}.
 * Created in bulk by {@code SubmissionService.submit}; never updated; never
 * deleted (except cascade if the parent submission row is ever deleted,
 * which the codebase does not currently do).
 *
 * <p>Distinct from {@link ProjectWorkspaceFile} (mutable, live editing
 * surface). The duplication is intentional — once a submission is created
 * the intern keeps editing the workspace files, and the evaluator MUST be
 * able to review exactly the bytes that were submitted.</p>
 */
@Entity
@Table(
        name = "workspace_submitted_files",
        indexes = {
                @Index(name = "idx_wsf_submission",
                        columnList = "submission_id, path")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceSubmittedFile {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;
}
