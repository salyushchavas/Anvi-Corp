package com.anvicorp.api.event.project;

import com.anvicorp.api.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Reporting Manager scheduled the viva — project status is now
 * {@code PENDING_VIVA}.
 */
public final class ProjectMarkedPendingVivaEvent extends DomainEvent {

    private final UUID projectId;
    private final UUID reportingManagerUserId;
    private final Instant scheduledAt;

    public ProjectMarkedPendingVivaEvent(UUID projectId,
                                         UUID reportingManagerUserId,
                                         Instant scheduledAt) {
        this.projectId = projectId;
        this.reportingManagerUserId = reportingManagerUserId;
        this.scheduledAt = scheduledAt;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getReportingManagerUserId() {
        return reportingManagerUserId;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }
}
