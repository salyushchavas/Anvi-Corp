package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * ERM Phase 7 — fired when an ERM (or system listener) updates a single
 * checklist item. Carried so downstream listeners (audit / notifications)
 * can react without each call site duplicating dispatch logic.
 */
@Getter
public final class ExitChecklistItemUpdatedEvent extends DomainEvent {
    private final UUID exitRecordId;
    private final UUID checklistItemId;
    private final String itemKey;
    private final String previousStatus;
    private final String newStatus;
    private final UUID actorUserId;

    public ExitChecklistItemUpdatedEvent(UUID exitRecordId, UUID checklistItemId,
                                          String itemKey, String previousStatus,
                                          String newStatus, UUID actorUserId) {
        this.exitRecordId = exitRecordId;
        this.checklistItemId = checklistItemId;
        this.itemKey = itemKey;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.actorUserId = actorUserId;
    }
}
