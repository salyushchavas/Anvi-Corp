package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 4 — fires AFTER_COMMIT when ERM resends or sends a reminder. */
@Getter
public final class OfferReminderEvent extends DomainEvent {

    private final UUID offerId;
    private final UUID applicantUserId;
    private final UUID actorUserId;
    /** REMINDER_SENT (no expiry change) | RESENT (with new expiry). */
    private final String mode;
    private final String reasonCode;

    public OfferReminderEvent(UUID offerId, UUID applicantUserId, UUID actorUserId,
                               String mode, String reasonCode) {
        this.offerId = offerId;
        this.applicantUserId = applicantUserId;
        this.actorUserId = actorUserId;
        this.mode = mode;
        this.reasonCode = reasonCode;
    }
}
