package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fired AFTER_COMMIT of {@code OfferIdmsSigningService.sendOffer}. Phase 7
 * will fan this out into notification rows; for now the offer's own
 * NotificationService.sendOfferExtended path covers the email.
 */
@Getter
public final class OfferSentEvent extends DomainEvent {

    private final UUID offerId;
    private final UUID applicationId;
    private final UUID applicantUserId;

    public OfferSentEvent(UUID offerId, UUID applicationId, UUID applicantUserId) {
        this.offerId = offerId;
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
    }
}
