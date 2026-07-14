package com.anvicorp.api.event.project;

import com.anvicorp.api.event.DomainEvent;

import java.util.UUID;

/**
 * Intern submitted their workspace for review — project moved
 * {@code IN_PROGRESS → SUBMITTED}, a {@code WorkspaceSubmission} row exists,
 * and the evaluator should be notified.
 *
 * <p>Published from {@code SubmissionService.submit}. Distinct from the
 * legacy {@code ProjectService.submit} which calls the email service inline
 * (kept unchanged for back-compat with the older intern UI).</p>
 */
public final class ProjectSubmittedEvent extends DomainEvent {

    private final UUID projectId;
    private final UUID submissionId;
    private final UUID internUserId;

    public ProjectSubmittedEvent(UUID projectId, UUID submissionId, UUID internUserId) {
        this.projectId = projectId;
        this.submissionId = submissionId;
        this.internUserId = internUserId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public UUID getInternUserId() {
        return internUserId;
    }
}
