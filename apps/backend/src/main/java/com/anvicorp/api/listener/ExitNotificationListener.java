package com.anvicorp.api.listener;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.event.ExitFeedbackSubmittedEvent;
import com.anvicorp.api.event.ExitInitiatedEvent;
import com.anvicorp.api.event.GithubAccessRevokedEvent;
import com.anvicorp.api.intern.OrgTeamResolver;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Phase 8 — Phase 7 dispatcher fan-out for the three exit events. All
 * dispatches AFTER_COMMIT + per-recipient best-effort.
 *
 * <ul>
 *   <li>{@link ExitInitiatedEvent} — intern + ERM + Trainer + Evaluator + Manager.</li>
 *   <li>{@link ExitFeedbackSubmittedEvent} — ERM + Manager.</li>
 *   <li>{@link GithubAccessRevokedEvent} — ERM (summary line).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExitNotificationListener {

    private static final String INTERN_DASH = "/careers/intern";
    private static final String ERM_DASH = "/careers/erm/exits";
    private static final String TRAINER_DASH = "/careers/trainer";
    private static final String EVALUATOR_DASH = "/careers/reporting-manager";
    private static final String MANAGER_DASH = "/careers/manager";

    private final UserNotificationDispatcher dispatcher;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final OrgTeamResolver orgTeamResolver;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExitInitiated(ExitInitiatedEvent event) {
        if (event == null || event.getInternUserId() == null) return;
        InternLifecycle lc = lifecycleRepository
                .findById(event.getInternLifecycleId()).orElse(null);
        String internName = lookupName(event.getInternUserId());
        String typeHuman = humanType(event.getExitType());
        String intHeader = "Your internship has concluded";
        String intBody = "Concluded on " + event.getExitDate()
                + " (" + typeHuman + "). Visit your dashboard to download records "
                + "and share feedback.";
        String detailUrl = "/careers/erm/exits/" + event.getExitRecordId();

        dispatch(event.getInternUserId(), "EXIT_INITIATED", event.getInternUserId(),
                intHeader, intBody, INTERN_DASH + "/exit/summary");

        if (lc == null) return;
        dispatch(lc.getErmId(), "EXIT_INITIATED", event.getInternUserId(),
                "Exit initiated for " + safe(internName) + " (" + typeHuman + ")",
                "Open the exit page to complete the checklist.",
                detailUrl);
        // Trainer + Evaluator route through OrgTeamResolver so the
        // org-wide singletons get the exit notification even when the
        // per-intern FK was never stamped at activation time.
        dispatch(orgTeamResolver.resolveTrainerId(lc), "EXIT_INITIATED", event.getInternUserId(),
                "Your intern " + safe(internName) + " was moved to inactive",
                typeHuman + " on " + event.getExitDate() + ".",
                TRAINER_DASH);
        dispatch(orgTeamResolver.resolveEvaluatorId(lc), "EXIT_INITIATED", event.getInternUserId(),
                "Intern " + safe(internName) + " moved to inactive",
                typeHuman + " on " + event.getExitDate() + ".",
                EVALUATOR_DASH);
        dispatch(lc.getManagerId(), "EXIT_INITIATED", event.getInternUserId(),
                "Intern " + safe(internName) + " exited (" + typeHuman + ")",
                "Effective " + event.getExitDate() + ".",
                MANAGER_DASH);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExitFeedbackSubmitted(ExitFeedbackSubmittedEvent event) {
        if (event == null || event.getInternUserId() == null) return;
        InternLifecycle lc = lifecycleRepository.findByUserId(event.getInternUserId())
                .orElse(null);
        String internName = lookupName(event.getInternUserId());
        String detailUrl = "/careers/erm/exits/" + event.getExitRecordId();
        if (lc != null) {
            dispatch(lc.getErmId(), "EXIT_FEEDBACK_SUBMITTED", event.getInternUserId(),
                    safe(internName) + " submitted exit feedback",
                    "Open the exit page to review.",
                    detailUrl);
            dispatch(lc.getManagerId(), "EXIT_FEEDBACK_SUBMITTED", event.getInternUserId(),
                    safe(internName) + " submitted exit feedback",
                    "Open the exit page to review.",
                    detailUrl);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGithubAccessRevoked(GithubAccessRevokedEvent event) {
        if (event == null || event.getInternUserId() == null) return;
        InternLifecycle lc = lifecycleRepository.findByUserId(event.getInternUserId())
                .orElse(null);
        String internName = lookupName(event.getInternUserId());
        if (lc != null) {
            dispatch(lc.getErmId(), "GITHUB_ACCESS_REVOKED", event.getInternUserId(),
                    "GitHub access: " + event.getReposSucceeded() + "/"
                            + event.getReposAttempted() + " repos revoked for "
                            + safe(internName),
                    event.getSummary(),
                    "/careers/erm/exits/" + event.getExitRecordId());
        }
    }

    private void dispatch(UUID recipientId, String eventType, UUID subjectUserId,
                           String title, String body, String url) {
        if (recipientId == null) return;
        try {
            dispatcher.dispatch(recipientId, eventType, subjectUserId,
                    title, body, url, false);
        } catch (Exception e) {
            log.debug("[ExitNotify] dispatch failed for {}: {}", eventType, e.getMessage());
        }
    }

    private String lookupName(UUID userId) {
        if (userId == null) return null;
        try {
            return userRepository.findById(userId).map(User::getFullName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String s) {
        return s != null ? s : "intern";
    }

    private static String humanType(String t) {
        if (t == null) return "exit";
        return switch (t) {
            case "COMPLETED" -> "completed";
            case "RESIGNED" -> "resigned";
            case "TERMINATED" -> "terminated";
            case "EXTENDED" -> "extended";
            default -> t.toLowerCase();
        };
    }
}
