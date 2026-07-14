package com.anvicorp.api.event;

import lombok.Getter;

import java.util.List;
import java.util.UUID;

/** ERM Phase 8 — fired AFTER_COMMIT when ERM assigns a packet to an
 *  intern. Listener fans out DOCUMENT_PACKET_ASSIGNED email + in-app
 *  notification with the template title list. */
@Getter
public final class DocumentPacketAssignedEvent extends DomainEvent {
    private final UUID packetId;
    private final UUID internLifecycleId;
    private final UUID internUserId;
    private final UUID assignedByUserId;
    private final List<String> templateTitles;

    public DocumentPacketAssignedEvent(UUID packetId, UUID internLifecycleId,
                                        UUID internUserId, UUID assignedByUserId,
                                        List<String> templateTitles) {
        this.packetId = packetId;
        this.internLifecycleId = internLifecycleId;
        this.internUserId = internUserId;
        this.assignedByUserId = assignedByUserId;
        this.templateTitles = templateTitles;
    }
}
