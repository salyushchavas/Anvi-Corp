package com.anvicorp.api.workspace.infrastructure;

import com.anvicorp.api.workspace.domain.ReviewOutcome;
import com.anvicorp.api.workspace.domain.WorkspaceSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceSubmissionRepository
        extends JpaRepository<WorkspaceSubmission, UUID> {

    /** Submissions for one project, newest first. */
    List<WorkspaceSubmission> findByProjectIdOrderBySubmittedAtDesc(UUID projectId);

    /** Latest submission across all outcomes — used to flag "the one to look at". */
    Optional<WorkspaceSubmission> findFirstByProjectIdOrderBySubmittedAtDesc(UUID projectId);

    /**
     * Latest submission whose outcome matches — primarily called with
     * {@link ReviewOutcome#PENDING} to find "the submission under review".
     * At most one such row should exist per project at any time (the project
     * status is SUBMITTED while one exists; only the next submit creates a
     * second PENDING and only after the prior is APPROVED/RETURNED).
     */
    Optional<WorkspaceSubmission> findFirstByProjectIdAndReviewOutcomeOrderBySubmittedAtDesc(
            UUID projectId, ReviewOutcome reviewOutcome);

    /** Highest submissionNumber on the project, for assigning the next one. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(MAX(s.submissionNumber), 0) FROM WorkspaceSubmission s "
                    + "WHERE s.projectId = :projectId")
    int maxSubmissionNumber(
            @org.springframework.data.repository.query.Param("projectId") UUID projectId);
}
