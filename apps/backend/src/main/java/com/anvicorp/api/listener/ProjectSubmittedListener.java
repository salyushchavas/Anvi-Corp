package com.anvicorp.api.listener;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.event.ProjectSubmittedEvent;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT fan-out for {@link ProjectSubmittedEvent}. The intern has
 * submitted (or re-submitted) work; we dispatch an in-app notification
 * to the owning Trainer so the new submission surfaces in their Pending
 * Reviews queue immediately (the queue itself reads from
 * {@code project_submissions} — this is purely the bell + Messages
 * heads-up so the trainer doesn't have to refresh to notice).
 *
 * <p>Best-effort: notification failure never rolls back the submit.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectSubmittedListener {

    private final UserRepository userRepository;
    private final UserNotificationDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(ProjectSubmittedEvent e) {
        if (e == null) return;
        if (e.getTrainerUserId() == null) {
            log.debug("[ProjectSubmitted] no trainer resolvable for assignment={} — skipping notify",
                    e.getAssignmentId());
            return;
        }
        try {
            String internName = "An intern";
            try {
                User intern = e.getInternUserId() != null
                        ? userRepository.findById(e.getInternUserId()).orElse(null)
                        : null;
                if (intern != null && intern.getFullName() != null
                        && !intern.getFullName().isBlank()) {
                    internName = intern.getFullName();
                }
            } catch (Exception ignored) {}

            String projectLabel = e.getProjectTitle() != null
                    && !e.getProjectTitle().isBlank()
                    ? e.getProjectTitle() : "their project";
            boolean isResubmit = e.getVersion() > 1;
            String title = isResubmit
                    ? internName + " re-submitted " + projectLabel + " (v" + e.getVersion() + ")"
                    : internName + " submitted " + projectLabel;
            String body = isResubmit
                    ? "Updated deliverables are ready for review. Open Pending Reviews to take a look."
                    : "New work is ready for review. Open Pending Reviews to take a look.";
            dispatcher.dispatch(e.getTrainerUserId(), "PROJECT_SUBMITTED",
                    e.getInternUserId(), title, body,
                    "/careers/trainer/pending-reviews", false);
        } catch (Exception ex) {
            log.warn("[ProjectSubmitted] trainer notify failed (non-fatal): {}", ex.getMessage());
        }
    }
}
