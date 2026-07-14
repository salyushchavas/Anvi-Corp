package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * ERM Phase 6 — fires once when {@code ExceptionScanJob} OPENs a fresh
 * {@code ExceptionRecord}. Listener dispatches one in-app notification to
 * the intern's assigned ERM (or to the new-record assignee if pre-routed).
 * Subsequent scan ticks update {@code last_seen_at} silently — they do NOT
 * re-fire this event.
 */
@Getter
public final class ExceptionOpenedEvent extends DomainEvent {
    private final UUID exceptionRecordId;
    private final UUID subjectUserId;
    private final UUID internLifecycleId;
    private final String exceptionType;
    private final String severity;

    public ExceptionOpenedEvent(UUID exceptionRecordId,
                                 UUID subjectUserId,
                                 UUID internLifecycleId,
                                 String exceptionType,
                                 String severity) {
        this.exceptionRecordId = exceptionRecordId;
        this.subjectUserId = subjectUserId;
        this.internLifecycleId = internLifecycleId;
        this.exceptionType = exceptionType;
        this.severity = severity;
    }
}
