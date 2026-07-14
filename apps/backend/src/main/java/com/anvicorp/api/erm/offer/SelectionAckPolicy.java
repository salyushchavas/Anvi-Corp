package com.anvicorp.api.erm.offer;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Single source of truth for the Phase E selection-acknowledgment gate.
 * Both surfaces that touch this state — the ERM Send-Offer 409 in
 * {@link ErmOfferService#createAndSend} and the
 * {@code SelectionAckCard} emission in
 * {@code InternDashboardService.computeSelectionContext} — must read it
 * through this component, never inline.
 *
 * <p>Prior to extracting this policy the two surfaces diverged:
 * the gate keyed off the latest completed Interview's decision +
 * {@code Application.selectionAcknowledgedAt}, while the card added a
 * third guard requiring {@code User.lifecycleStatus == INTERVIEW_COMPLETED}.
 * That produced a deadlock for SELECTED interns whose lifecycle status
 * wasn't exactly INTERVIEW_COMPLETED — the offer 409'd, the card was
 * hidden, the intern had no way to acknowledge.</p>
 */
@Component
@RequiredArgsConstructor
public class SelectionAckPolicy {

    private final InterviewRepository interviewRepository;

    /**
     * Most recent {@link InterviewStatus#COMPLETED} interview on the
     * application, ordered by {@code scheduledAt} desc. Returned for
     * downstream callers that need to read the applicant-visible notes
     * off the same interview the gate evaluates.
     */
    public Optional<Interview> latestCompletedInterview(Application app) {
        if (app == null || app.getId() == null) return Optional.empty();
        return interviewRepository
                .findByApplicationIdOrderByScheduledAtDesc(app.getId()).stream()
                .filter(i -> i.getStatus() == InterviewStatus.COMPLETED)
                .findFirst();
    }

    /**
     * Whether the latest completed interview has been APPROVED by a
     * Manager (the new hire-approval gate). Previously this read
     * {@code Interview.decision == "SELECTED"} as set by the ERM; the
     * gate now lives on {@code Interview.managerHireDecision}, which a
     * Manager flips to {@code APPROVED} from the Hire Approvals queue.
     * Historical rows where the ERM had already recorded SELECTED were
     * back-filled to APPROVED by {@code SchemaFixupRunner} so in-flight
     * offers don't freeze.
     */
    public boolean isSelected(Application app) {
        return latestCompletedInterview(app)
                .map(iv -> "APPROVED".equalsIgnoreCase(iv.getManagerHireDecision()))
                .orElse(false);
    }

    /**
     * THE shared predicate. True iff Send Offer would 409 with
     * "requires the intern's selection acknowledgment" — i.e. the
     * candidate is {@link #isSelected(Application) SELECTED} on their
     * latest completed interview AND {@code selectionAcknowledgedAt}
     * is still null. The intern's SelectionAckCard MUST be visible
     * whenever this returns true; otherwise both sides deadlock.
     */
    public boolean needsAck(Application app) {
        return app != null
                && isSelected(app)
                && app.getSelectionAcknowledgedAt() == null;
    }
}
