package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * ERM Phase 7 — fired when a MANAGER (or SUPER_ADMIN) overrides a stuck
 * exit, marking the remaining PENDING checklist items as WAIVED with a
 * single reason. Listener notifies the ERM + intern.
 */
@Getter
public final class ExitManagerOverrideEvent extends DomainEvent {
    private final UUID exitRecordId;
    private final UUID internLifecycleId;
    private final UUID subjectUserId;
    private final UUID managerUserId;
    private final String reasonCode;
    private final int itemsWaived;

    public ExitManagerOverrideEvent(UUID exitRecordId, UUID internLifecycleId,
                                     UUID subjectUserId, UUID managerUserId,
                                     String reasonCode, int itemsWaived) {
        this.exitRecordId = exitRecordId;
        this.internLifecycleId = internLifecycleId;
        this.subjectUserId = subjectUserId;
        this.managerUserId = managerUserId;
        this.reasonCode = reasonCode;
        this.itemsWaived = itemsWaived;
    }
}
