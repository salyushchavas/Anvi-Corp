package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 6 — immutable per-event audit history for each
 * {@link ExceptionRecord}. Mirrors OnboardingReviewLog /
 * OfferEventLog. Actor is null for system-emitted events (OPENED via
 * scan job, AUTO_RESOLVED, SEEN).
 */
@Entity
@Table(name = "exception_event_logs", indexes = {
        @Index(name = "idx_exception_event_logs_record",
                columnList = "exception_record_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExceptionEventLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "exception_record_id", nullable = false)
    private UUID exceptionRecordId;

    /** Null = system / scheduled job. */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    /** OPENED | REOPENED | ASSIGNED | REASSIGNED | IN_PROGRESS_SET |
     *  NOTE_ADDED | RESOLVED | DISMISSED | AUTO_RESOLVED | SEEN. */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "previous_status", length = 20)
    private String previousStatus;

    @Column(name = "new_status", length = 20)
    private String newStatus;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    /** ERM-only — never surfaced to the intern. */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
