package com.anvicorp.api.event;

import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * ERM Phase 2 — fires AFTER_COMMIT on every ERM decision against an
 * Application. The Phase 7 dispatcher fan-out + email template
 * rendering live in {@code ApplicationDecisionListener}.
 *
 * <p>{@code applicantVisibleMessage} carries the rendered template body
 * that was persisted to {@code application_decision_logs} (kept verbatim
 * so the timeline + the email match).</p>
 */
@Getter
public final class ApplicationDecisionEvent extends DomainEvent {

    /** SHORTLIST | HOLD | REQUEST_INFO | REJECT | RESUME_FROM_HOLD. */
    private final String decision;
    private final UUID applicationId;
    private final UUID applicantUserId;
    private final UUID decidedByUserId;
    private final String reasonCode;
    private final List<String> infoRequestedFields;
    private final String applicantVisibleMessage;
    private final String renderedSubject;

    public ApplicationDecisionEvent(String decision,
                                     UUID applicationId,
                                     UUID applicantUserId,
                                     UUID decidedByUserId,
                                     String reasonCode,
                                     List<String> infoRequestedFields,
                                     String applicantVisibleMessage,
                                     String renderedSubject) {
        this.decision = decision;
        this.applicationId = applicationId;
        this.applicantUserId = applicantUserId;
        this.decidedByUserId = decidedByUserId;
        this.reasonCode = reasonCode;
        this.infoRequestedFields = infoRequestedFields;
        this.applicantVisibleMessage = applicantVisibleMessage;
        this.renderedSubject = renderedSubject;
    }
}
