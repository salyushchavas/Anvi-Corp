package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 4 — fires AFTER_COMMIT on offer void. */
@Getter
public final class OfferVoidedEvent extends DomainEvent {

    private final UUID offerId;
    private final UUID applicantUserId;
    private final UUID actorUserId;
    private final String reasonCode;
    private final String reasonText;
    private final boolean notifyApplicant;

    public OfferVoidedEvent(UUID offerId, UUID applicantUserId, UUID actorUserId,
                             String reasonCode, String reasonText, boolean notifyApplicant) {
        this.offerId = offerId;
        this.applicantUserId = applicantUserId;
        this.actorUserId = actorUserId;
        this.reasonCode = reasonCode;
        this.reasonText = reasonText;
        this.notifyApplicant = notifyApplicant;
    }
}
