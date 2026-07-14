package com.anvicorp.api.event.evaluation;

import com.anvicorp.api.event.DomainEvent;
import lombok.Getter;

import java.util.UUID;

@Getter
public final class EvaluationPublishedEvent extends DomainEvent {
    private final UUID evaluationId;
    private final UUID internUserId;
    private final UUID evaluatorUserId;
    private final String evaluationType;

    public EvaluationPublishedEvent(UUID evaluationId, UUID internUserId,
                                     UUID evaluatorUserId, String evaluationType) {
        this.evaluationId = evaluationId;
        this.internUserId = internUserId;
        this.evaluatorUserId = evaluatorUserId;
        this.evaluationType = evaluationType;
    }
}
