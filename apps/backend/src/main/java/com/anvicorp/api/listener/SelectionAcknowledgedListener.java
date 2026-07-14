package com.anvicorp.api.listener;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.event.SelectionAcknowledgedEvent;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * AFTER_COMMIT fan-out for {@link SelectionAcknowledgedEvent}. The intern
 * has clicked "Receive my offer letter" on their dashboard, which
 * unblocks {@code ErmOfferService.createAndSend}. We dispatch an in-app
 * notification to the owning ERM (or all ERMs when the application has
 * no owner) so they know the candidate is ready for the offer to be
 * issued via the existing offer flow.
 *
 * <p>Best-effort: notification failure never rolls back the ack.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelectionAcknowledgedListener {

    private final UserRepository userRepository;
    private final UserNotificationDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAcknowledged(SelectionAcknowledgedEvent e) {
        if (e == null || e.getApplicationId() == null) return;
        try {
            User applicant = e.getApplicantUserId() != null
                    ? userRepository.findById(e.getApplicantUserId()).orElse(null)
                    : null;
            String applicantName = applicant != null && applicant.getFullName() != null
                    ? applicant.getFullName() : "The candidate";
            String jobTitle = e.getJobTitle() != null && !e.getJobTitle().isBlank()
                    ? e.getJobTitle() : "the role";
            String title = applicantName + " is ready for their offer letter";
            String body = applicantName + " acknowledged selection for "
                    + jobTitle + ". Issue the offer via Offers → Create.";
            String actionUrl = "/careers/erm/offers";

            if (e.getErmOwnerUserId() != null) {
                dispatcher.dispatch(e.getErmOwnerUserId(), "SELECTION_ACKNOWLEDGED",
                        e.getApplicantUserId(), title, body, actionUrl, false);
                return;
            }
            // Unowned application — broadcast to every active ERM so it
            // doesn't sit unclaimed.
            List<User> erms = userRepository.findByRole(UserRole.ERM);
            for (User u : erms) {
                if (u == null || u.getId() == null) continue;
                try {
                    dispatcher.dispatch(u.getId(), "SELECTION_ACKNOWLEDGED",
                            e.getApplicantUserId(), title, body, actionUrl, false);
                } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            log.warn("[SelectionAck] ERM notify failed (non-fatal): {}", ex.getMessage());
        }
    }
}
