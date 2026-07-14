package com.anvicorp.api.intern;

import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.OfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Phase 7 hourly offer expiry job. Two passes:
 *
 * <ol>
 *   <li>Reminder pass — for offers in SENT with {@code expires_at} within
 *       the next 24h, fire a "sign within 24h" reminder to the applicant
 *       + the ERM who created the offer. Idempotency lives on the
 *       UserNotification side; this job re-fires harmlessly since the
 *       dispatcher writes per recipient per call.</li>
 *   <li>Expiry pass — for offers in SENT with {@code expires_at} in the
 *       past, set status=EXPIRED + notify ERM. Signing is in-house via
 *       IDMS so there's no external envelope to void.</li>
 * </ol>
 *
 * <p>Lifecycle status is NOT regressed; ERM decides whether to re-offer
 * or move the application to REJECTED separately.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfferExpiryJob {

    private final OfferRepository offerRepository;
    private final UserNotificationDispatcher notificationDispatcher;

    @Scheduled(cron = "0 0 * * * *") // every hour, on the hour
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        Instant reminderCutoff = now.plus(Duration.ofHours(24));

        try {
            List<Offer> sentOffers = offerRepository.findByStatusAndExpiresAtBefore(
                    OfferStatus.SENT, reminderCutoff);
            int reminded = 0;
            int expired = 0;
            for (Offer o : sentOffers) {
                if (o.getExpiresAt() == null) continue;
                if (o.getExpiresAt().isBefore(now)) {
                    expireOne(o);
                    expired++;
                } else {
                    remindOne(o);
                    reminded++;
                }
            }
            if (reminded > 0 || expired > 0) {
                log.info("[OfferExpiry] reminded={} expired={}", reminded, expired);
            }
        } catch (Exception e) {
            log.warn("[OfferExpiry] sweep failed (non-fatal): {}", e.getMessage());
        }
    }

    private void remindOne(Offer offer) {
        java.util.UUID applicantId = offer.getApplication() != null
                && offer.getApplication().getCandidate() != null
                && offer.getApplication().getCandidate().getUser() != null
                ? offer.getApplication().getCandidate().getUser().getId() : null;
        if (applicantId != null) {
            notificationDispatcher.dispatch(applicantId, "OFFER_EXPIRING",
                    applicantId,
                    "Sign your offer within 24 hours",
                    "Your offer expires soon. Open the Offer page to sign before the deadline.",
                    "/careers/intern/offer", false);
        }
        if (offer.getCreatedBy() != null) {
            notificationDispatcher.dispatch(offer.getCreatedBy(), "OFFER_EXPIRING",
                    applicantId,
                    "Offer expiring within 24h",
                    "Offer for applicant has not yet been signed.",
                    "/careers/erm", false);
        }
    }

    private void expireOne(Offer offer) {
        // No external envelope to void — signing is in-house via IDMS.
        offer.setStatus(OfferStatus.EXPIRED);
        offer.setVoidedAt(Instant.now());
        if (offer.getVoidedReason() == null || offer.getVoidedReason().isBlank()) {
            offer.setVoidedReason("Auto-expired past expiry_at");
        }
        offerRepository.save(offer);

        java.util.UUID applicantId = offer.getApplication() != null
                && offer.getApplication().getCandidate() != null
                && offer.getApplication().getCandidate().getUser() != null
                ? offer.getApplication().getCandidate().getUser().getId() : null;
        if (applicantId != null) {
            notificationDispatcher.dispatch(applicantId, "OFFER_EXPIRED",
                    applicantId,
                    "Your offer has expired",
                    "The signing window passed before the offer was signed. "
                            + "Contact ERM to discuss next steps.",
                    "/careers/intern/offer", false);
        }
        if (offer.getCreatedBy() != null) {
            notificationDispatcher.dispatch(offer.getCreatedBy(), "OFFER_EXPIRED",
                    applicantId,
                    "Offer expired without signature",
                    "Auto-expired by the system. Re-offer or move to REJECTED as appropriate.",
                    "/careers/erm", false);
        }
        log.info("[OfferExpiry] expired offer={}", offer.getId());
    }
}
