package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when an intern submits (or re-submits) work for a
 * project assignment. The downstream listener notifies the owning
 * Trainer so the new submission surfaces in their Pending Reviews queue.
 */
@Getter
public final class ProjectSubmittedEvent extends DomainEvent {

    private final UUID assignmentId;
    private final UUID projectId;
    private final UUID submissionId;
    private final UUID internUserId;
    private final UUID trainerUserId;
    private final String projectTitle;
    private final int version;

    public ProjectSubmittedEvent(UUID assignmentId, UUID projectId, UUID submissionId,
                                  UUID internUserId, UUID trainerUserId,
                                  String projectTitle, int version) {
        this.assignmentId = assignmentId;
        this.projectId = projectId;
        this.submissionId = submissionId;
        this.internUserId = internUserId;
        this.trainerUserId = trainerUserId;
        this.projectTitle = projectTitle;
        this.version = version;
    }
}
