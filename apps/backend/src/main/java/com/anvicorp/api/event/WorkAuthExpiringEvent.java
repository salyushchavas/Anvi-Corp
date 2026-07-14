package com.anvicorp.api.event;

import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * ERM Phase 5 — fired by the daily ComplianceAlertJob when an intern's
 * work-auth expiration falls inside the alert window. Idempotent at the
 * listener layer via the (recipient, eventType, YYYY-MM-DD) dedup key.
 */
@Getter
public final class WorkAuthExpiringEvent extends DomainEvent {
    private final UUID userId;
    private final String workAuthType;
    private final LocalDate expirationDate;
    private final int daysUntilExpiration;

    public WorkAuthExpiringEvent(UUID userId, String workAuthType,
                                 LocalDate expirationDate, int daysUntilExpiration) {
        this.userId = userId;
        this.workAuthType = workAuthType;
        this.expirationDate = expirationDate;
        this.daysUntilExpiration = daysUntilExpiration;
    }
}
