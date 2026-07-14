package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * ERM Phase 6 — fires when the scan job AUTO_RESOLVES a record because
 * its underlying condition is no longer reported (last_seen_at &lt;
 * now - 30 min). Listener notifies the ERM owner so they know the
 * self-healing happened.
 */
@Getter
public final class ExceptionAutoResolvedEvent extends DomainEvent {
    private final UUID exceptionRecordId;
    private final UUID subjectUserId;
    private final UUID internLifecycleId;
    private final String exceptionType;

    public ExceptionAutoResolvedEvent(UUID exceptionRecordId,
                                       UUID subjectUserId,
                                       UUID internLifecycleId,
                                       String exceptionType) {
        this.exceptionRecordId = exceptionRecordId;
        this.subjectUserId = subjectUserId;
        this.internLifecycleId = internLifecycleId;
        this.exceptionType = exceptionType;
    }
}
