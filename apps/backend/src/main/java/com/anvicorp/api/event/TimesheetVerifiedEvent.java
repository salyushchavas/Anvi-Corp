package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when an ERM verifies a SUBMITTED timesheet week
 * (SUBMITTED → VERIFIED). Drives the chain notification to the owning
 * Manager that the week is ready for approval.
 */
@Getter
public final class TimesheetVerifiedEvent extends DomainEvent {

    private final UUID timesheetId;
    private final UUID internUserId;
    private final UUID actorUserId;

    public TimesheetVerifiedEvent(UUID timesheetId, UUID internUserId, UUID actorUserId) {
        this.timesheetId = timesheetId;
        this.internUserId = internUserId;
        this.actorUserId = actorUserId;
    }
}
