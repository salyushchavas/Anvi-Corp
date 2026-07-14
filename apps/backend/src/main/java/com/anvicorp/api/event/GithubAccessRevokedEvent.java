package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Phase 8 — fires after the {@code GithubRevocationListener} has attempted
 * to remove the intern from every repo they had collaborator access on.
 * Summary is human-friendly text the ERM sees on the exit detail page.
 */
@Getter
public final class GithubAccessRevokedEvent extends DomainEvent {

    private final UUID exitRecordId;
    private final UUID internUserId;
    private final int reposAttempted;
    private final int reposSucceeded;
    private final String summary;

    public GithubAccessRevokedEvent(UUID exitRecordId, UUID internUserId,
                                     int reposAttempted, int reposSucceeded,
                                     String summary) {
        this.exitRecordId = exitRecordId;
        this.internUserId = internUserId;
        this.reposAttempted = reposAttempted;
        this.reposSucceeded = reposSucceeded;
        this.summary = summary;
    }
}
