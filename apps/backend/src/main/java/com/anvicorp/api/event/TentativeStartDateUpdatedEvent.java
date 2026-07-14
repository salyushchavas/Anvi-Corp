package com.anvicorp.api.event;

import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

/** ERM Phase 4 — fires AFTER_COMMIT on tentative-start-date update. */
@Getter
public final class TentativeStartDateUpdatedEvent extends DomainEvent {

    private final UUID internLifecycleId;
    private final UUID internUserId;
    private final UUID updatedByUserId;
    private final LocalDate previousDate;
    private final LocalDate newDate;

    public TentativeStartDateUpdatedEvent(UUID internLifecycleId, UUID internUserId,
                                           UUID updatedByUserId,
                                           LocalDate previousDate, LocalDate newDate) {
        this.internLifecycleId = internLifecycleId;
        this.internUserId = internUserId;
        this.updatedByUserId = updatedByUserId;
        this.previousDate = previousDate;
        this.newDate = newDate;
    }
}
