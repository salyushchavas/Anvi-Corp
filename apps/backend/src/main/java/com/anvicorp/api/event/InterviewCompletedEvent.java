package com.anvicorp.api.event;

import com.anvicorp.api.enums.InterviewRecommendation;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Change 4 — fired after an interview scorecard finalizes (not on drafts).
 *
 * <p>Listeners run {@code @TransactionalEventListener(AFTER_COMMIT)} so the
 * acknowledgment never lands while the scorecard write is still pending. The
 * acknowledgment listener is best-effort: a failure logs and continues without
 * rolling back the scorecard.</p>
 */
@Getter
public final class InterviewCompletedEvent extends DomainEvent {

    private final UUID interviewId;
    private final UUID applicationId;
    private final UUID candidateUserId;
    private final String candidateEmail;
    private final InterviewRecommendation recommendation;
    private final Instant completedAt;
    private final UUID completedByUserId;

    public InterviewCompletedEvent(UUID interviewId,
                                   UUID applicationId,
                                   UUID candidateUserId,
                                   String candidateEmail,
                                   InterviewRecommendation recommendation,
                                   Instant completedAt,
                                   UUID completedByUserId) {
        this.interviewId = interviewId;
        this.applicationId = applicationId;
        this.candidateUserId = candidateUserId;
        this.candidateEmail = candidateEmail;
        this.recommendation = recommendation;
        this.completedAt = completedAt;
        this.completedByUserId = completedByUserId;
    }
}
