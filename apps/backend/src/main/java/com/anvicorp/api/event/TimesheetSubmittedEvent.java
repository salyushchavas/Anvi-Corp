package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when the intern submits (or re-submits) a timesheet
 * week. Drives the chain notification to the owning ERM that the week
 * is ready for verification.
 */
@Getter
public final class TimesheetSubmittedEvent extends DomainEvent {

    private final UUID timesheetId;
    private final UUID internUserId;
    private final UUID actorUserId;

    public TimesheetSubmittedEvent(UUID timesheetId, UUID internUserId, UUID actorUserId) {
        this.timesheetId = timesheetId;
        this.internUserId = internUserId;
        this.actorUserId = actorUserId;
    }
}
