package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when an ERM verifies a SUBMITTED weekly report
 * (SUBMITTED → VERIFIED). Drives the chain notification to the owning
 * Evaluator that the report is ready for approval. Mirrors
 * {@link TimesheetVerifiedEvent}.
 */
@Getter
public final class WeeklyReportVerifiedEvent extends DomainEvent {

    private final UUID reportId;
    private final UUID internUserId;
    private final UUID actorUserId;

    public WeeklyReportVerifiedEvent(UUID reportId, UUID internUserId, UUID actorUserId) {
        this.reportId = reportId;
        this.internUserId = internUserId;
        this.actorUserId = actorUserId;
    }
}
