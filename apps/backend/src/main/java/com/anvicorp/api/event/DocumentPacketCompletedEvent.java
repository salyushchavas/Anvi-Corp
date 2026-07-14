package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 8 — fired AFTER_COMMIT when the last DocumentTask in a
 *  packet flips to ACCEPTED (or WAIVED), the packet auto-completes,
 *  and the lifecycle advances ONBOARDING_ASSIGNED → ONBOARDING_ACCEPTED.
 *  Listener renders the DOCUMENT_PACKET_COMPLETED welcome email. */
@Getter
public final class DocumentPacketCompletedEvent extends DomainEvent {
    private final UUID packetId;
    private final UUID internLifecycleId;
    private final UUID internUserId;

    public DocumentPacketCompletedEvent(UUID packetId, UUID internLifecycleId,
                                         UUID internUserId) {
        this.packetId = packetId;
        this.internLifecycleId = internLifecycleId;
        this.internUserId = internUserId;
    }
}
