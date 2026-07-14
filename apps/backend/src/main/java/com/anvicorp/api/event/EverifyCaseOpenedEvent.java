package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * ERM Phase 5 — fires after ERM records a manually-opened E-Verify case
 * (EVerifyStatus = OPEN) for an applicant's I-9. Triggers the applicant
 * email "Your E-Verify case has been opened" and an in-app notification.
 */
@Getter
public final class EverifyCaseOpenedEvent extends DomainEvent {
    private final UUID caseId;
    private final UUID i9FormId;
    private final UUID applicantUserId;

    public EverifyCaseOpenedEvent(UUID caseId, UUID i9FormId, UUID applicantUserId) {
        this.caseId = caseId;
        this.i9FormId = i9FormId;
        this.applicantUserId = applicantUserId;
    }
}
