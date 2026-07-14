package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * ERM Phase 5 — fires after ERM updates an E-Verify case status. The new
 * status drives which template (TNC vs AUTHORIZED) is rendered downstream;
 * unrecognised transitions are silently ignored by the listener.
 */
@Getter
public final class EverifyStatusChangedEvent extends DomainEvent {
    private final UUID caseId;
    private final UUID applicantUserId;
    private final String previousStatus;
    private final String newStatus;

    public EverifyStatusChangedEvent(UUID caseId, UUID applicantUserId,
                                     String previousStatus, String newStatus) {
        this.caseId = caseId;
        this.applicantUserId = applicantUserId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
    }
}
