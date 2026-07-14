package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 — one row per message in a support-ticket thread. ERM-side
 * notes can be marked {@code internalOnly} so they're hidden from the
 * opener; the controller strips internal-only rows for non-staff readers.
 */
@Entity
@Table(name = "support_ticket_replies", indexes = {
        @Index(name = "idx_support_ticket_replies_ticket_created",
                columnList = "ticket_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportTicketReply {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "internal_only", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private Boolean internalOnly = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
