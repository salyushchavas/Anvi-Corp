package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 in-app notification inbox row. Distinct from the existing
 * {@code SentNotification} (which is the per-email idempotency ledger).
 * Each event the platform raises produces one of these per recipient so
 * the bell + Messages page can render real per-user feeds with
 * read-tracking + deep-link CTAs.
 */
@Entity
@Table(name = "user_notifications", indexes = {
        @Index(name = "idx_user_notifications_recipient_read",
                columnList = "recipient_user_id, read_at"),
        @Index(name = "idx_user_notifications_recipient_created",
                columnList = "recipient_user_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** The user the event is about; may differ from the recipient. */
    @Column(name = "subject_user_id")
    private UUID subjectUserId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** Deep link the bell row + Messages row link to. */
    @Column(name = "action_url", length = 500)
    private String actionUrl;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "email_sent", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private Boolean emailSent = false;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
