package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 3 — fires AFTER_COMMIT on interview cancellation. */
@Getter
public final class InterviewCancelledEvent extends DomainEvent {

    private final UUID interviewId;
    private final UUID applicationId;
    private final UUID applicantUserId;
    private final UUID interviewerUserId;
    private final UUID cancelledByUserId;
    private final String reasonCode;
    private final String reasonText;
    private final boolean notifyApplicant;

    public InterviewCancelledEvent(UUID interviewId, UUID applicationId,
                                    UUID applicantUserId, UUID interviewerUserId,
                                    UUID cancelledByUserId,
                                    String reasonCode, String reasonText,
                                    boolean notifyApplicant) {
        this.interviewId = interviewId;
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
        this.interviewerUserId = interviewerUserId;
        this.cancelledByUserId = cancelledByUserId;
        this.reasonCode = reasonCode;
        this.reasonText = reasonText;
        this.notifyApplicant = notifyApplicant;
    }
}
