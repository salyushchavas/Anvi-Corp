package com.anvicorp.api.service;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.SentNotification;
import com.anvicorp.api.event.InterviewCompletedEvent;
import com.anvicorp.api.notification.NotificationEventType;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.SentNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Change 4 — applicant-facing acknowledgment fired after an interview
 * scorecard finalizes.
 *
 * <p>One {@code sent_notifications} row per (interview, recipient) — the
 * unique constraint on {@code (event_type, target_id)} makes retries naturally
 * idempotent, so a scorecard re-submission (rare race) is safe.</p>
 *
 * <p>Best-effort end-to-end: listener failure logs without rolling back the
 * scorecard write (AFTER_COMMIT phase + own transaction).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewAcknowledgmentService {

    private final SentNotificationRepository sentNotificationRepository;
    private final UserNotificationDispatcher userNotificationDispatcher;
    private final InternLifecycleRepository internLifecycleRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInterviewCompleted(InterviewCompletedEvent event) {
        if (event == null) return;
        UUID interviewId = event.getInterviewId();
        if (interviewId == null) return;
        String recipient = event.getCandidateEmail();
        if (recipient == null || recipient.isBlank()) {
            log.debug("Skipping INTERVIEW_COMPLETED ack — no recipient email for interview {}",
                    interviewId);
            return;
        }
        recordAck(interviewId, recipient);
        dispatchInApp(event, interviewId);
    }

    private void dispatchInApp(InterviewCompletedEvent event, UUID interviewId) {
        UUID applicantUserId = event.getCandidateUserId();
        if (applicantUserId == null) return;
        try {
            userNotificationDispatcher.dispatch(applicantUserId,
                    NotificationEventType.INTERVIEW_COMPLETED.name(),
                    applicantUserId,
                    "Interview completed",
                    "Thanks for completing your interview. We'll follow up with next steps soon.",
                    "/careers/intern/interviews/" + interviewId, true);
        } catch (Exception e) {
            log.debug("[UserNotif] INTERVIEW_COMPLETED applicant dispatch failed: {}",
                    e.getMessage());
        }
        InternLifecycle lc;
        try {
            lc = internLifecycleRepository.findByUserId(applicantUserId).orElse(null);
        } catch (Exception e) {
            log.debug("[UserNotif] lifecycle lookup failed for {}: {}",
                    applicantUserId, e.getMessage());
            return;
        }
        if (lc == null) {
            log.debug("[UserNotif] no InternLifecycle for {} — staff INTERVIEW_COMPLETED skipped",
                    applicantUserId);
            return;
        }
        dispatchStaff(lc.getErmId(), applicantUserId, interviewId, "/careers/erm");
        dispatchStaff(lc.getManagerId(), applicantUserId, interviewId, "/careers/manager");
    }

    private void dispatchStaff(UUID staffId, UUID applicantUserId, UUID interviewId, String url) {
        if (staffId == null) {
            log.debug("[UserNotif] INTERVIEW_COMPLETED staff slot null for applicant {} — skip",
                    applicantUserId);
            return;
        }
        try {
            userNotificationDispatcher.dispatch(staffId,
                    NotificationEventType.INTERVIEW_COMPLETED.name(),
                    applicantUserId,
                    "Interview completed",
                    "An applicant's interview was completed; scorecard finalized.",
                    url, true);
        } catch (Exception e) {
            log.debug("[UserNotif] INTERVIEW_COMPLETED staff dispatch failed: {}",
                    e.getMessage());
        }
    }

    /** Public for direct unit tests. */
    public void recordAck(java.util.UUID interviewId, String recipient) {
        if (sentNotificationRepository
                .existsByEventTypeAndTargetId(NotificationEventType.INTERVIEW_COMPLETED, interviewId)) {
            log.debug("INTERVIEW_COMPLETED already recorded for {}", interviewId);
            return;
        }
        try {
            sentNotificationRepository.save(SentNotification.builder()
                    .eventType(NotificationEventType.INTERVIEW_COMPLETED)
                    .targetId(interviewId)
                    .recipient(recipient)
                    .build());
            log.info("Recorded INTERVIEW_COMPLETED ack for {} -> {}", interviewId, recipient);
        } catch (DataIntegrityViolationException dive) {
            log.info("Duplicate INTERVIEW_COMPLETED ledger row for {} — already recorded.",
                    interviewId);
        } catch (Exception e) {
            log.warn("Failed to record INTERVIEW_COMPLETED for {} (non-fatal): {}",
                    interviewId, e.getMessage());
        }
    }
}
