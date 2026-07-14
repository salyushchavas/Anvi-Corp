package com.anvicorp.api.dto.admin;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEntryResponse {
    private UUID id;
    private Instant timestamp;
    private UUID actorId;
    /** Resolved from the User table by AuditLog.userId; null when actor is unknown. */
    private String actorName;
    private String action;
    private String entityType;
    private UUID entityId;
    /**
     * Short human summary of the change — derived from the after-JSON when present,
     * else null. Long payloads are truncated to keep the table responsive.
     */
    private String details;
}
