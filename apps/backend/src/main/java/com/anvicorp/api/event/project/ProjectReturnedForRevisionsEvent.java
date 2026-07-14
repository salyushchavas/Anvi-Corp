package com.anvicorp.api.event.project;

import com.anvicorp.api.event.DomainEvent;

import java.util.UUID;

/**
 * A reviewer (technical supervisor or reporting manager) sent the project
 * back for revisions. Status moves to {@code IN_PROGRESS}; the intern
 * resubmits.
 */
public final class ProjectReturnedForRevisionsEvent extends DomainEvent {

    private final UUID projectId;
    private final UUID reviewerUserId;
    private final String reason;

    public ProjectReturnedForRevisionsEvent(UUID projectId, UUID reviewerUserId, String reason) {
        this.projectId = projectId;
        this.reviewerUserId = reviewerUserId;
        this.reason = reason;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getReviewerUserId() {
        return reviewerUserId;
    }

    public String getReason() {
        return reason;
    }
}
