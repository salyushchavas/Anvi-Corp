package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 4 — fires AFTER_COMMIT when ERM assigns the full reporting structure. */
@Getter
public final class ReportingStructureAssignedEvent extends DomainEvent {

    private final UUID internLifecycleId;
    private final UUID internUserId;
    private final UUID trainerUserId;
    private final UUID evaluatorUserId;
    private final UUID managerUserId;
    private final UUID assignedByUserId;

    public ReportingStructureAssignedEvent(UUID internLifecycleId, UUID internUserId,
                                            UUID trainerUserId, UUID evaluatorUserId,
                                            UUID managerUserId, UUID assignedByUserId) {
        this.internLifecycleId = internLifecycleId;
        this.internUserId = internUserId;
        this.trainerUserId = trainerUserId;
        this.evaluatorUserId = evaluatorUserId;
        this.managerUserId = managerUserId;
        this.assignedByUserId = assignedByUserId;
    }
}
