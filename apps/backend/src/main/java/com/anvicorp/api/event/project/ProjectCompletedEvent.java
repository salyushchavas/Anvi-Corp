package com.anvicorp.api.event.project;

import com.anvicorp.api.event.DomainEvent;

import java.util.UUID;

/**
 * Project hit the terminal {@code COMPLETED} status.
 *
 * <p>Published from BOTH workflow paths so downstream listeners (offboarding,
 * portfolio link, badges) don't have to care which reviewer signed it:</p>
 * <ul>
 *   <li>Two-role flow — Reporting Manager calls
 *       {@code ProjectWorkflowService.completeAfterViva}.</li>
 *   <li>Legacy flow — Tech supervisor calls
 *       {@code ProjectService.complete} (SUBMITTED → COMPLETED direct).</li>
 * </ul>
 *
 * <p>{@code closedByUserId} is the actor who triggered the transition
 * (Reporting Manager in the two-role case, Technical Evaluator in the
 * legacy case).</p>
 */
public final class ProjectCompletedEvent extends DomainEvent {

    private final UUID projectId;
    private final UUID closedByUserId;

    public ProjectCompletedEvent(UUID projectId, UUID closedByUserId) {
        this.projectId = projectId;
        this.closedByUserId = closedByUserId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getClosedByUserId() {
        return closedByUserId;
    }
}
