package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** Phase 8 — fires when an exited intern submits the one-time feedback survey. */
@Getter
public final class ExitFeedbackSubmittedEvent extends DomainEvent {

    private final UUID feedbackId;
    private final UUID exitRecordId;
    private final UUID internUserId;

    public ExitFeedbackSubmittedEvent(UUID feedbackId, UUID exitRecordId, UUID internUserId) {
        this.feedbackId = feedbackId;
        this.exitRecordId = exitRecordId;
        this.internUserId = internUserId;
    }
}
