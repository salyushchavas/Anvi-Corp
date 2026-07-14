package com.anvicorp.api.event;

import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 8 — fires when ERM moves an intern to INACTIVE_INTERN. Drives
 * notification fan-out and the GitHub-revocation listener.
 */
@Getter
public final class ExitInitiatedEvent extends DomainEvent {

    private final UUID exitRecordId;
    private final UUID internLifecycleId;
    private final UUID internUserId;
    private final UUID initiatedByUserId;
    private final String exitType;
    private final LocalDate exitDate;

    public ExitInitiatedEvent(UUID exitRecordId, UUID internLifecycleId,
                              UUID internUserId, UUID initiatedByUserId,
                              String exitType, LocalDate exitDate) {
        this.exitRecordId = exitRecordId;
        this.internLifecycleId = internLifecycleId;
        this.internUserId = internUserId;
        this.initiatedByUserId = initiatedByUserId;
        this.exitType = exitType;
        this.exitDate = exitDate;
    }
}
