package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.service.EngagementActivationService;
import com.anvicorp.api.service.EngagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-off recovery backfill that unsticks engagements sitting in
 * {@code PENDING_COMPLIANCE} even though every compliance item is already
 * in its done-state. These rows exist because there was a window where no
 * HR-facing "Activate Engagement" affordance existed yet, so HR had no
 * way to flip the gate even though the candidate had finished everything.
 *
 * <p>Idempotent + gated by {@code app.backfill.unstuck-onboarding-enabled=true}
 * (env {@code BACKFILL_UNSTUCK_ONBOARDING}). Default false. Operator flips
 * it on, redeploys once to sweep, then flips it back off. Safe to re-run.</p>
 *
 * <p>This is <b>recovery, not policy</b>. The live flow is:
 * compliance flips → HR sees "Activate Engagement" on the queue (driven by
 * {@code GET /api/v1/engagements/{id}/activation-readiness}) → HR clicks
 * → {@code POST /api/v1/engagements/{id}/mark-ready}. Nothing auto-flips
 * on a compliance write.</p>
 *
 * <p>Audit row stamps the action {@code RECOVERY_BACKFILL_UNSTICK} so
 * backfilled rows are distinguishable from HR-driven activations
 * (which stamp {@code MARK_READY}).</p>
 */
@Component
@Order(20)
@Slf4j
public class UnstickOnboardingRunner implements CommandLineRunner {

    private static final String AUDIT_ACTION = "RECOVERY_BACKFILL_UNSTICK";

    private final boolean enabled;
    private final EngagementRepository engagementRepository;
    private final EngagementActivationService engagementActivationService;
    private final EngagementService engagementService;

    public UnstickOnboardingRunner(
            @Value("${app.backfill.unstuck-onboarding-enabled:false}") boolean enabled,
            EngagementRepository engagementRepository,
            EngagementActivationService engagementActivationService,
            EngagementService engagementService) {
        this.enabled = enabled;
        this.engagementRepository = engagementRepository;
        this.engagementActivationService = engagementActivationService;
        this.engagementService = engagementService;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("[UnstickOnboardingRunner] skipped — disabled "
                    + "(set app.backfill.unstuck-onboarding-enabled=true to enable).");
            return;
        }
        int scanned = 0;
        int advanced = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<Engagement> pending = engagementRepository
                    .findByStatus(EngagementStatus.PENDING_COMPLIANCE);
            scanned = pending.size();
            for (Engagement e : pending) {
                try {
                    if (!engagementActivationService.isReady(e)) {
                        skipped++;
                        continue;
                    }
                    engagementService.transitionToSystem(
                            e,
                            EngagementStatus.READY_TO_START,
                            AUDIT_ACTION,
                            null);
                    log.info("[UnstickOnboardingRunner] advanced engagement {} "
                            + "(candidate {})",
                            e.getId(),
                            e.getCandidate() != null ? e.getCandidate().getId() : null);
                    advanced++;
                } catch (Exception ex) {
                    failed++;
                    log.warn("[UnstickOnboardingRunner] failed to advance engagement {} "
                            + "(non-fatal): {}", e.getId(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[UnstickOnboardingRunner] sweep failed (non-fatal): {}",
                    e.getMessage(), e);
        }
        log.info("[UnstickOnboardingRunner] summary: scanned={}, advanced={}, "
                + "skipped={}, failed={}",
                scanned, advanced, skipped, failed);
    }
}
