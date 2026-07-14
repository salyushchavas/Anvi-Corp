package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 support ticket. Opener is any authenticated user; ERM /
 * SUPER_ADMIN can triage and reply. {@link SupportTicketReply}
 * carries the threaded conversation.
 */
@Entity
@Table(name = "support_tickets", indexes = {
        @Index(name = "idx_support_tickets_opener_status",
                columnList = "opener_user_id, status"),
        @Index(name = "idx_support_tickets_status_created",
                columnList = "status, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportTicket {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "opener_user_id", nullable = false)
    private UUID openerUserId;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** TECHNICAL | ACCOUNT | ONBOARDING | PAYROLL | OTHER. */
    @Column(name = "category", nullable = false, length = 30)
    private String category;

    /** LOW | NORMAL | HIGH | URGENT — opener selects. */
    @Column(name = "priority", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'NORMAL'")
    @Builder.Default
    private String priority = "NORMAL";

    /** OPEN | IN_PROGRESS | RESOLVED | CLOSED. */
    @Column(name = "status", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'OPEN'")
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "assigned_to_id")
    private UUID assignedToId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
