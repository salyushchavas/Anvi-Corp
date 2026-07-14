package com.anvicorp.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker base for in-process domain events published through Spring's
 * {@code ApplicationEventPublisher}.
 *
 * <p>POJO by design — no Spring annotations, no JPA, no logging. Events
 * carry the minimum data a listener needs to fan out side effects (emails,
 * audit rows, downstream domain actions). Heavyweight payloads (e.g. full
 * entity graphs) are intentionally NOT included; listeners re-read by id
 * inside their own transaction.</p>
 *
 * <h2>Publishing model</h2>
 * Producers (services in this codebase's {@code application} layer) call
 * {@code ApplicationEventPublisher.publishEvent(...)}. Listeners are
 * {@code @EventListener} or {@code @TransactionalEventListener} beans living
 * outside the service — side effects MUST NOT live in the service itself.
 *
 * <h2>Ordering + transactional semantics</h2>
 * Default Spring behaviour is synchronous + same-transaction. Listeners that
 * must wait for the producing transaction to commit (audit + email are good
 * examples) annotate with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public abstract class DomainEvent {

    private final UUID eventId = UUID.randomUUID();
    private final Instant occurredAt = Instant.now();

    public UUID getEventId() {
        return eventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
