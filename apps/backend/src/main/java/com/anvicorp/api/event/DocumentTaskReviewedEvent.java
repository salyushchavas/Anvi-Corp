package com.anvicorp.api.event;

import lombok.Getter;

import java.util.UUID;

/** ERM Phase 8 — fired AFTER_COMMIT when ERM accepts / rejects /
 *  resend-requests a submission. Listener renders one of three
 *  DOCUMENT_TASK_* templates per decision and dispatches to the
 *  intern. */
@Getter
public final class DocumentTaskReviewedEvent extends DomainEvent {
    private final UUID taskId;
    private final UUID packetId;
    private final UUID internUserId;
    private final UUID reviewerUserId;
    private final String decision;            // ACCEPT | REJECT | RESEND_REQUEST
    private final String reasonCode;
    private final String ermComments;
    private final String templateTitle;

    public DocumentTaskReviewedEvent(UUID taskId, UUID packetId, UUID internUserId,
                                      UUID reviewerUserId, String decision,
                                      String reasonCode, String ermComments,
                                      String templateTitle) {
        this.taskId = taskId;
        this.packetId = packetId;
        this.internUserId = internUserId;
        this.reviewerUserId = reviewerUserId;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.ermComments = ermComments;
        this.templateTitle = templateTitle;
    }
}
