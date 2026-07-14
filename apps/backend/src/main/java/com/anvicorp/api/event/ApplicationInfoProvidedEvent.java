package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 2 — applicant closed the loop on INFO_REQUESTED. */
@Getter
public final class ApplicationInfoProvidedEvent extends DomainEvent {

    private final UUID applicationId;
    private final UUID applicantUserId;
    private final UUID ermOwnerId;

    public ApplicationInfoProvidedEvent(UUID applicationId,
                                         UUID applicantUserId,
                                         UUID ermOwnerId) {
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
        this.ermOwnerId = ermOwnerId;
    }
}
