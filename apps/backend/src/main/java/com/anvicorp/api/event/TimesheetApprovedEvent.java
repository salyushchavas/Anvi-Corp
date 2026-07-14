package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when the Manager approves a VERIFIED timesheet
 * week (VERIFIED → APPROVED). Drives the chain notification to the
 * intern that their week was approved.
 */
@Getter
public final class TimesheetApprovedEvent extends DomainEvent {

    private final UUID timesheetId;
    private final UUID internUserId;
    private final UUID actorUserId;

    public TimesheetApprovedEvent(UUID timesheetId, UUID internUserId, UUID actorUserId) {
        this.timesheetId = timesheetId;
        this.internUserId = internUserId;
        this.actorUserId = actorUserId;
    }
}
