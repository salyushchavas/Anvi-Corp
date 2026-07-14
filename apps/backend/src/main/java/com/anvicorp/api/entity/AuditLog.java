package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    /**
     * Optional — the user whose data the action AFFECTED (subject), distinct
     * from {@link #userId} (the actor). Populated on new writes that touch
     * code paths where the subject is known up front; null for historical
     * rows and for write paths that don't yet populate it. Per-user audit
     * queries fall back to entity-type/entity-id resolution for those.
     *
     * <p>Additive column — handled by Hibernate ddl-auto=update. Nullable so
     * existing rows stay valid without backfill.
     */
    @Column(name = "subject_user_id")
    private UUID subjectUserId;

    @Column(nullable = false)
    private String action;

    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(columnDefinition = "TEXT")
    private String beforeJson;

    @Column(columnDefinition = "TEXT")
    private String afterJson;

    private String ipAddress;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @PrePersist
    void onCreate() {
        this.timestamp = Instant.now();
    }
}
