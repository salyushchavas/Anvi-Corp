package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 3 — fires AFTER_COMMIT when ERM creates a new interview. */
@Getter
public final class InterviewScheduledEvent extends DomainEvent {

    private final UUID interviewId;
    private final UUID applicationId;
    private final UUID applicantUserId;
    private final UUID interviewerUserId;
    private final UUID createdByUserId;

    public InterviewScheduledEvent(UUID interviewId, UUID applicationId,
                                    UUID applicantUserId, UUID interviewerUserId,
                                    UUID createdByUserId) {
        this.interviewId = interviewId;
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
        this.interviewerUserId = interviewerUserId;
        this.createdByUserId = createdByUserId;
    }
}
