package com.anvicorp.api.event.evaluation;

import com.anvicorp.api.event.DomainEvent;
import lombok.Getter;

import java.util.UUID;

@Getter
public final class EvaluationAmendedEvent extends DomainEvent {
    private final UUID evaluationId;
    private final UUID internUserId;
    private final UUID evaluatorUserId;
    private final int newVersion;

    public EvaluationAmendedEvent(UUID evaluationId, UUID internUserId,
                                   UUID evaluatorUserId, int newVersion) {
        this.evaluationId = evaluationId;
        this.internUserId = internUserId;
        this.evaluatorUserId = evaluatorUserId;
        this.newVersion = newVersion;
    }
}
