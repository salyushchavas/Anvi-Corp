package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 8 — fired AFTER_COMMIT when intern uploads a filled
 *  document. Triggers the review queue refresh + an in-app nudge
 *  to the packet's assigning ERM. */
@Getter
public final class DocumentTaskSubmittedEvent extends DomainEvent {
    private final UUID taskId;
    private final UUID packetId;
    private final UUID internLifecycleId;
    private final UUID internUserId;
    private final String templateTitle;

    public DocumentTaskSubmittedEvent(UUID taskId, UUID packetId,
                                       UUID internLifecycleId, UUID internUserId,
                                       String templateTitle) {
        this.taskId = taskId;
        this.packetId = packetId;
        this.internLifecycleId = internLifecycleId;
        this.internUserId = internUserId;
        this.templateTitle = templateTitle;
    }
}
