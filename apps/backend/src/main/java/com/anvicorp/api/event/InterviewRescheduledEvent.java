package com.anvicorp.api.event;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** ERM Phase 3 — fires AFTER_COMMIT on reschedule + interviewer change. */
@Getter
public final class InterviewRescheduledEvent extends DomainEvent {

    private final UUID interviewId;
    private final UUID applicationId;
    private final UUID applicantUserId;
    private final UUID interviewerUserId;
    private final UUID rescheduledByUserId;
    private final Instant previousScheduledAt;
    private final Instant newScheduledAt;
    private final String reasonCode;
    private final boolean notifyApplicant;
    private final boolean notifyInterviewer;

    public InterviewRescheduledEvent(UUID interviewId, UUID applicationId,
                                      UUID applicantUserId, UUID interviewerUserId,
                                      UUID rescheduledByUserId,
                                      Instant previousScheduledAt, Instant newScheduledAt,
                                      String reasonCode,
                                      boolean notifyApplicant, boolean notifyInterviewer) {
        this.interviewId = interviewId;
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
        this.interviewerUserId = interviewerUserId;
        this.rescheduledByUserId = rescheduledByUserId;
        this.previousScheduledAt = previousScheduledAt;
        this.newScheduledAt = newScheduledAt;
        this.reasonCode = reasonCode;
        this.notifyApplicant = notifyApplicant;
        this.notifyInterviewer = notifyInterviewer;
    }
}
