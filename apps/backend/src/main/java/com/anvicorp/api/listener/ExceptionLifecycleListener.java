package com.anvicorp.api.listener;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.event.ExceptionAutoResolvedEvent;
import com.anvicorp.api.event.ExceptionOpenedEvent;
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
 * ERM Phase 6 — fans out the once-per-record OPEN notification + the
 * AUTO_RESOLVE feed entry. Idempotency comes from the
 * {@code sent_notifications} ledger inside the dispatcher; this listener
 * just routes to the right recipient (intern_lifecycles.erm_id).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExceptionLifecycleListener {

    private static final String ERM_ESCALATIONS = "/careers/erm/escalations";

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final UserNotificationDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpened(ExceptionOpenedEvent e) {
        if (e == null || e.getInternLifecycleId() == null) return;
        UUID ermId = resolveErm(e.getInternLifecycleId());
        if (ermId == null) return;
        String internLabel = internName(e.getSubjectUserId());
        try {
            dispatcher.dispatch(
                    ermId,
                    "EXCEPTION_OPENED",
                    e.getSubjectUserId(),
                    "Exception: " + humanType(e.getExceptionType()),
                    severityPrefix(e.getSeverity()) + internLabel
                            + " — opened by the detection job.",
                    ERM_ESCALATIONS + "/" + e.getExceptionRecordId(),
                    false);
        } catch (Exception ex) {
            log.debug("[ExceptionListener] open dispatch failed: {}", ex.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAutoResolved(ExceptionAutoResolvedEvent e) {
        if (e == null || e.getInternLifecycleId() == null) return;
        UUID ermId = resolveErm(e.getInternLifecycleId());
        if (ermId == null) return;
        String internLabel = internName(e.getSubjectUserId());
        try {
            dispatcher.dispatch(
                    ermId,
                    "EXCEPTION_AUTO_RESOLVED",
                    e.getSubjectUserId(),
                    "Exception auto-resolved: " + humanType(e.getExceptionType()),
                    internLabel + " — detector no longer reports this condition.",
                    ERM_ESCALATIONS + "/" + e.getExceptionRecordId(),
                    false);
        } catch (Exception ex) {
            log.debug("[ExceptionListener] auto-resolve dispatch failed: {}",
                    ex.getMessage());
        }
    }

    private UUID resolveErm(UUID lifecycleId) {
        try {
            return lifecycleRepository.findById(lifecycleId)
                    .map(InternLifecycle::getErmId)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String internName(UUID userId) {
        if (userId == null) return "intern";
        try {
            return userRepository.findById(userId)
                    .map(User::getFullName)
                    .orElse("intern");
        } catch (Exception e) {
            return "intern";
        }
    }

    private static String humanType(String type) {
        if (type == null) return "Exception";
        return type.replace('_', ' ').toLowerCase();
    }

    private static String severityPrefix(String severity) {
        if (severity == null) return "";
        return "[" + severity + "] ";
    }
}
