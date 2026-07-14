package com.anvicorp.api.entity;

import com.anvicorp.api.notification.NotificationEventType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency ledger for outbound notifications. One row per real send.
 *
 * <p>The {@code (event_type, target_id)} pair is unique — the DB enforces
 * "send exactly once per (event, target)" so a status that flips back and
 * forth (e.g. Shortlisted → Applied → Shortlisted) only emails the first
 * time. Re-sends after a manual delete require deleting the row.</p>
 *
 * <p>This is additive — created via ddl-auto. No existing data needs
 * backfill; legacy events (verification code, password reset) bypass this
 * ledger entirely because they don't need idempotency.</p>
 */
@Entity
@Table(
        name = "sent_notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sent_notification_event_target",
                columnNames = {"event_type", "target_id"}
        ),
        indexes = {
                @Index(name = "idx_sent_notification_target", columnList = "target_id"),
                @Index(name = "idx_sent_notification_recipient", columnList = "recipient")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentNotification {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private NotificationEventType eventType;

    /** UUID of the domain row this send is about (application / interview / offer / engagement). */
    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;
}
