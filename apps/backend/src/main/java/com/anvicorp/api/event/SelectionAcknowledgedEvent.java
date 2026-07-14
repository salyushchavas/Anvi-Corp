package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Fires AFTER_COMMIT when the intern clicks "Receive my offer letter" on
 * their dashboard, acknowledging post-interview selection. The downstream
 * listener notifies the owning ERM that the candidate is ready for the
 * offer to be issued via the existing offer flow.
 */
@Getter
public final class SelectionAcknowledgedEvent extends DomainEvent {

    private final UUID applicationId;
    private final UUID applicantUserId;
    private final UUID ermOwnerUserId;
    private final String jobTitle;

    public SelectionAcknowledgedEvent(UUID applicationId, UUID applicantUserId,
                                       UUID ermOwnerUserId, String jobTitle) {
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
        this.ermOwnerUserId = ermOwnerUserId;
        this.jobTitle = jobTitle;
    }
}
