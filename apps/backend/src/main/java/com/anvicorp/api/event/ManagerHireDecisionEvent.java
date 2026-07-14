package com.anvicorp.api.event;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Fired when a Manager actions a hire decision from the Hire Approvals
 * queue. Replaces the role that {@link InterviewCompletedEvent} used to
 * play for the SELECTED/REJECTED applicant emails — ERM-complete no
 * longer carries that decision, the Manager does.
 *
 * <p>Listeners (AFTER_COMMIT):</p>
 * <ul>
 *   <li>Applicant email: SELECTED welcome ("Receive my offer letter")
 *       on APPROVED, rejection email on REJECTED.</li>
 *   <li>ERM notification: "approved to hire" or "no-hire" so the ERM
 *       knows to proceed (offer) or stand down.</li>
 * </ul>
 */
@Getter
public final class ManagerHireDecisionEvent extends DomainEvent {

    private final UUID interviewId;
    private final UUID applicationId;
    private final UUID candidateUserId;
    private final String candidateEmail;
    /** {@code APPROVED} or {@code REJECTED}. */
    private final String decision;
    private final UUID managerUserId;
    /** The ERM who originally submitted the scorecard — recipient of
     *  the manager-decision notification. */
    private final UUID ermUserId;
    private final Instant decidedAt;

    public ManagerHireDecisionEvent(UUID interviewId,
                                    UUID applicationId,
                                    UUID candidateUserId,
                                    String candidateEmail,
                                    String decision,
                                    UUID managerUserId,
                                    UUID ermUserId,
                                    Instant decidedAt) {
        this.interviewId = interviewId;
        this.applicationId = applicationId;
        this.candidateUserId = candidateUserId;
        this.candidateEmail = candidateEmail;
        this.decision = decision;
        this.managerUserId = managerUserId;
        this.ermUserId = ermUserId;
        this.decidedAt = decidedAt;
    }
}
