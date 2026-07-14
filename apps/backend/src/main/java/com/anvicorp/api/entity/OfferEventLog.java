package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 4 — immutable history of every offer-envelope event.
 * Mirrors the InterviewEventLog pattern.
 */
@Entity
@Table(name = "offer_event_logs", indexes = {
        @Index(name = "idx_offer_event_logs_offer",
                columnList = "offer_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfferEventLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    /**
     * CREATED | SENT | REMINDER_SENT | RESENT | SIGNED | VOIDED |
     * DECLINED | EXPIRED | NOTES_UPDATED | START_DATE_UPDATED |
     * CLEARED_FOR_REOFFER.
     */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    /** ERM-only — never serialised to INTERN. */
    @Column(name = "reason_text", columnDefinition = "TEXT")
    private String reasonText;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
