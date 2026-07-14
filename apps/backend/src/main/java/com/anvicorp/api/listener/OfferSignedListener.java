package com.anvicorp.api.listener;

import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.event.OfferSignedEvent;
import com.anvicorp.api.notification.NotificationService;
import com.anvicorp.api.repository.OfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the welcome email after the offer-signed transaction commits.
 * Best-effort: a send failure logs but never rolls back the signature row
 * or the Employee ID assignment (both already committed by the time this
 * listener fires).
 *
 * <p>Phase 7 will extend this to also write a SentNotification row with
 * type {@code OFFER_SIGNED_WELCOME} for the Recent Activity feed; for
 * Phase 3 the NotificationService idempotency table already records the
 * send under {@code OFFER_ACCEPTED}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfferSignedListener {

    private final OfferRepository offerRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOfferSigned(OfferSignedEvent event) {
        try {
            Offer offer = offerRepository.findByIdWithGraph(event.getOfferId()).orElse(null);
            if (offer == null) {
                log.warn("OfferSignedListener: offer {} not found post-commit; skipping welcome email",
                        event.getOfferId());
                return;
            }
            // sendOfferAccepted is the existing welcome-confirmation pattern.
            // The doc calls this the "welcome email" — same semantics, plus
            // Phase 7 will add a SentNotification row for Recent Activity.
            notificationService.sendOfferAccepted(offer);
            log.info("[OfferSigned] welcome email queued for offer={} applicant={} employee_id={}",
                    event.getOfferId(), event.getApplicantUserId(), event.getEmployeeId());
        } catch (Exception e) {
            log.warn("OfferSignedListener welcome-email send failed (non-fatal) for {}: {}",
                    event.getOfferId(), e.getMessage());
        }
    }
}
