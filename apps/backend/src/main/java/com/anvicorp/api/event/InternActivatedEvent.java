package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

@Getter
public final class InternActivatedEvent extends DomainEvent {
    private final UUID userId;
    private final UUID internLifecycleId;

    public InternActivatedEvent(UUID userId, UUID internLifecycleId) {
        this.userId = userId;
        this.internLifecycleId = internLifecycleId;
    }
}
