package com.anvicorp.api.intern;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.event.InternActivatedEvent;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Activation job — every 10 minutes, scans users whose
 * {@code lifecycle_status = ONBOARDING_ACCEPTED} and flips any with a
 * SIGNED offer AND an ERM-set {@code joining_date <= today} to
 * {@code ACTIVE_INTERN}.
 *
 * <p>ERM Pass 2 reinstated the date gate, but pivoted from the offer's
 * {@code tentative_start_date} (a soft intention) to a separate
 * {@code intern_lifecycles.joining_date} that the ERM commits to on the
 * new-hire detail screen after onboarding docs are accepted. Doc
 * acceptance alone NO LONGER activates the intern — that flow only
 * advances them to {@code ONBOARDING_ACCEPTED}; the scheduled scan (or
 * the synchronous tryActivateIfReady hook fired at packet completion)
 * does the final flip once joining_date arrives.</p>
 *
 * <p>The manual {@link #activateNow} override remains the documented
 * early-start escape hatch: it bypasses both the offer + joining-date
 * checks but still requires {@code ONBOARDING_ACCEPTED}.</p>
 *
 * <p>{@code @EnableScheduling} is already on the application class.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternActivationJob {

    private final UserRepository userRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final OfferRepository offerRepository;
    private final InternLifecycleService internLifecycleService;
    private final ApplicationEventPublisher eventPublisher;
    private final ReportingStructureAutoLinker reportingStructureAutoLinker;

    @Scheduled(fixedDelay = 600_000L, initialDelay = 60_000L) // 10 min
    @Transactional
    public void activateReadyInterns() {
        // Pull candidates whose lifecycle is ONBOARDING_ACCEPTED — small set
        // in steady state; full scan is cheap with an index on lifecycle_status.
        List<User> ready;
        try {
            ready = userRepository.findByLifecycleStatus(
                    InternLifecycleStatus.ONBOARDING_ACCEPTED);
        } catch (Exception e) {
            log.warn("[ActivationJob] query failed (non-fatal): {}", e.getMessage());
            return;
        }
        if (ready.isEmpty()) {
            log.debug("[ActivationJob] no ready interns this tick");
            return;
        }

        int activated = 0;
        for (User u : ready) {
            try {
                if (tryActivateIfReady(u)) activated++;
            } catch (Exception e) {
                log.warn("[ActivationJob] per-user activate failed for {}: {}",
                        u.getId(), e.getMessage());
            }
        }
        if (activated > 0) {
            log.info("[ActivationJob] activated {} interns", activated);
        }
    }

    /**
     * ERM Pass 2 — single-user activation. Requires:
     * <ol>
     *   <li>lifecycle = {@code ONBOARDING_ACCEPTED} (docs accepted);</li>
     *   <li>a SIGNED (or legacy ACCEPTED) offer;</li>
     *   <li>{@code intern_lifecycles.joining_date} set by ERM AND
     *       {@code <= today}.</li>
     * </ol>
     *
     * <p>Doc acceptance now only walks the lifecycle to
     * {@code ONBOARDING_ACCEPTED}; this method is what flips them to
     * {@code ACTIVE_INTERN}. The synchronous call from
     * {@code DocumentPacketService.checkPacketCompletion()} still fires
     * but returns {@code false} until the ERM has set a joining_date
     * that has arrived — at which point the next scheduled scan (or a
     * manual ERM action) does the flip.</p>
     *
     * <p>Returns {@code true} iff the user was flipped. Safe to call
     * from event handlers — failure is silent so it never blocks the
     * calling transaction.</p>
     */
    @Transactional
    public boolean tryActivateIfReady(User user) {
        if (user == null) return false;
        if (user.getLifecycleStatus() != InternLifecycleStatus.ONBOARDING_ACCEPTED) {
            return false;
        }
        boolean hasSignedOffer;
        try {
            List<Offer> offers = offerRepository
                    .findByApplication_Candidate_User_IdOrderByCreatedAtDesc(user.getId());
            hasSignedOffer = offers.stream().anyMatch(o ->
                    o.getStatus() == com.anvicorp.api.enums.OfferStatus.SIGNED
                            || o.getStatus() == com.anvicorp.api.enums.OfferStatus.ACCEPTED);
        } catch (Exception e) {
            log.warn("[ActivationJob] offer lookup failed for {}: {}",
                    user.getId(), e.getMessage());
            return false;
        }
        if (!hasSignedOffer) return false;

        // joining_date gate — ERM-set, distinct from offer.tentativeStartDate.
        // Null = ERM hasn't committed; future = wait; today/past = activate now.
        InternLifecycle lc = internLifecycleRepository.findByUserId(user.getId()).orElse(null);
        if (lc == null) return false;
        java.time.LocalDate joiningDate = lc.getJoiningDate();
        if (joiningDate == null) return false;
        if (joiningDate.isAfter(java.time.LocalDate.now())) return false;

        return activateOneWithActor(user, null);
    }

    /**
     * ERM manual override. Bypasses the offer + joining_date gates
     * (documented exception for legitimate early starts) but still
     * requires {@code ONBOARDING_ACCEPTED} — the same atomic re-check
     * in {@link #activateOneWithActor} guarantees we never skip
     * document verification.
     */
    @Transactional
    public boolean activateNow(User user, java.util.UUID actorId) {
        if (user == null) return false;
        return activateOneWithActor(user, actorId);
    }

    private boolean activateOneWithActor(User u, java.util.UUID actorId) {
        InternLifecycle lc = internLifecycleRepository.findByUserId(u.getId()).orElse(null);
        if (lc == null) {
            log.warn("[ActivationJob] no InternLifecycle for user {} — skipping", u.getId());
            return false;
        }
        // Re-check status atomically to avoid racing the manual review path.
        if (u.getLifecycleStatus() != InternLifecycleStatus.ONBOARDING_ACCEPTED) {
            return false;
        }
        boolean advanced = internLifecycleService.advance(u,
                InternLifecycleStatus.ACTIVE_INTERN, actorId);
        if (!advanced) return false;
        Instant now = Instant.now();
        lc.setActiveStatus("ACTIVE");
        if (lc.getStartedAt() == null) lc.setStartedAt(now);
        // Backstop for the org-wide T/E auto-link. The same call runs at
        // offer-sign in OfferIdmsSigningService; running it again here is
        // idempotent (the linker preserves existing non-null assignments)
        // and covers the case where DEFAULT_TRAINER_EMAIL /
        // DEFAULT_EVALUATOR_EMAIL didn't resolve at sign-time (env var
        // unset or trainer user not yet seeded) but does now. Without
        // this, the Trainer/Evaluator queries — which filter on
        // intern_lifecycles.{trainer_id,evaluator_id} — would silently
        // omit the intern even after activation.
        try {
            reportingStructureAutoLinker.apply(lc, actorId, now);
        } catch (Exception e) {
            log.warn("[ActivationJob] auto-link backstop failed (non-fatal) for {}: {}",
                    u.getId(), e.getMessage());
        }
        internLifecycleRepository.save(lc);
        try {
            eventPublisher.publishEvent(new InternActivatedEvent(u.getId(), lc.getId()));
        } catch (Exception e) {
            log.warn("InternActivatedEvent publish failed: {}", e.getMessage());
        }
        log.info("[ActivationJob] activated user={} employee_id={}",
                u.getId(), lc.getEmployeeId());
        return true;
    }
}
