package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when an ERM or Manager rejects a timesheet week
 * back to the intern. The intern gets a notification with the reviewer
 * reason so they can correct and re-submit.
 */
@Getter
public final class TimesheetRejectedEvent extends DomainEvent {

    private final UUID timesheetId;
    private final UUID internUserId;
    private final UUID actorUserId;
    /** Status the row was in just before reject — SUBMITTED or VERIFIED. */
    private final String previousStatus;
    private final String reason;

    public TimesheetRejectedEvent(UUID timesheetId, UUID internUserId, UUID actorUserId,
                                   String previousStatus, String reason) {
        this.timesheetId = timesheetId;
        this.internUserId = internUserId;
        this.actorUserId = actorUserId;
        this.previousStatus = previousStatus;
        this.reason = reason;
    }
}
