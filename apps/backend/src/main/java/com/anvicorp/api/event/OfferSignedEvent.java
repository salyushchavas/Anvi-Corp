package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fired AFTER_COMMIT of {@code OfferIdmsSigningService.finalizeIdmsSigning}.
 * Listener sends the welcome email and writes a SentNotification row for
 * the Recent Activity feed.
 */
@Getter
public final class OfferSignedEvent extends DomainEvent {

    private final UUID offerId;
    private final UUID applicationId;
    private final UUID applicantUserId;
    private final String employeeId;

    public OfferSignedEvent(UUID offerId,
                            UUID applicationId,
                            UUID applicantUserId,
                            String employeeId) {
        this.offerId = offerId;
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
        this.employeeId = employeeId;
    }
}
