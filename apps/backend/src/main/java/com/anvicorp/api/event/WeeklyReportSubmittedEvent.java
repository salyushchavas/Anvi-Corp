package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when the intern submits (or re-submits) a weekly
 * report. Drives the chain notification to the owning ERM that the report
 * is ready for verification. Mirrors {@link TimesheetSubmittedEvent}.
 */
@Getter
public final class WeeklyReportSubmittedEvent extends DomainEvent {

    private final UUID reportId;
    private final UUID internUserId;
    private final UUID actorUserId;

    public WeeklyReportSubmittedEvent(UUID reportId, UUID internUserId, UUID actorUserId) {
        this.reportId = reportId;
        this.internUserId = internUserId;
        this.actorUserId = actorUserId;
    }
}
