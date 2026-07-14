package com.anvicorp.api.service;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I983Status;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.enums.OnboardingTaskStatus;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.OnboardingTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3 step 4 — the track router. Runs once at engagement creation, reads
 * the snapshot {@code track} + {@code authorizedToWork}, and:
 *
 *   - sets the engagement status (PENDING_COMPLIANCE or BLOCKED_NO_AUTHORIZATION),
 *   - augments the onboarding checklist with the per-track required tasks.
 *
 * This step does NOT touch I-9 / I-983 / E-Verify entities — those rows are
 * created by their existing services downstream (steps 5-7). The router only
 * sets up the checklist and the engagement phase.
 *
 * <b>Safe defaults (PRODUCT.md §8):</b> E-Verify is required by default ONLY
 * for STEM_OPT. Other tracks get E-Verify only if the operator opts in via
 * {@code app.compliance.everify-non-stem=true}. "No authorization" is a
 * process route to HR/legal — NOT a hire decision.
 */
@Service
@Slf4j
public class ComplianceRoutingService {

    // ── Task-key constants — keep grep-able and align with TRACK_TEMPLATES. ──

    private static final String TASK_HR_AUTHORIZATION_REVIEW = "HR_AUTHORIZATION_REVIEW";
    private static final String TASK_I983_DRAFT = "I983_DRAFT";
    private static final String TASK_I983_DSO_APPROVAL = "I983_DSO_APPROVAL";
    private static final String TASK_EVERIFY_BY_DAY_3 = "EVERIFY_BY_DAY_3";
    private static final String TASK_CPT_I20_VERIFY = "CPT_I20_VERIFY";

    private final EngagementService engagementService;
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final EVerifyCaseRepository everifyCaseRepository;
    private final OnboardingTaskRepository onboardingTaskRepository;

    /**
     * Whether to require E-Verify for non-STEM_OPT tracks. Default FALSE.
     * Driven by employer enrollment + state mandates — operations sets this
     * via {@code app.compliance.everify-non-stem} in application.properties.
     */
    private final boolean everifyEnabledForNonStem;

    public ComplianceRoutingService(
            EngagementService engagementService,
            I9FormRepository i9FormRepository,
            I983PlanRepository i983PlanRepository,
            EVerifyCaseRepository everifyCaseRepository,
            OnboardingTaskRepository onboardingTaskRepository,
            @Value("${app.compliance.everify-non-stem:false}") boolean everifyEnabledForNonStem) {
        this.engagementService = engagementService;
        this.i9FormRepository = i9FormRepository;
        this.i983PlanRepository = i983PlanRepository;
        this.everifyCaseRepository = everifyCaseRepository;
        this.onboardingTaskRepository = onboardingTaskRepository;
        this.everifyEnabledForNonStem = everifyEnabledForNonStem;
    }

    /**
     * Route a freshly-created engagement. Idempotent through downstream
     * services — the underlying task add is keyed on
     * {@code (candidate, taskKey, offer)} and a re-route adds nothing new.
     * Catches all internal exceptions so a routing bug never tears down the
     * outer accept/engagement flow.
     */
    @Transactional
    public void routeNewEngagement(Engagement engagement, User actor) {
        if (engagement == null || engagement.getCandidate() == null) {
            log.warn("Cannot route — engagement or candidate is null");
            return;
        }
        try {
            Candidate candidate = engagement.getCandidate();
            WorkAuthTrack track = engagement.getTrack(); // snapshot, may be null
            Boolean authorized = candidate.getAuthorizedToWork();

            if (isBlocked(authorized, track)) {
                UUID actorId = actor != null ? actor.getId() : null;
                engagementService.transitionToSystem(engagement,
                        EngagementStatus.BLOCKED_NO_AUTHORIZATION,
                        "ROUTE_NO_AUTH",
                        actorId);
                log.info("Engagement {} routed to BLOCKED_NO_AUTHORIZATION (authorized={}, track={})",
                        engagement.getId(), authorized, track);
                return;
            }

            List<String> extras = trackSpecificTaskKeys(track);
            log.info("Engagement {} routed (track={}, extras={})",
                    engagement.getId(), track, extras);
            // Status stays PENDING_COMPLIANCE — set by EngagementService.createForAcceptedOffer.
        } catch (Exception e) {
            log.warn("Routing failed for engagement {}: {}",
                    engagement.getId(), e.getMessage(), e);
        }
    }

    /**
     * Block when the candidate explicitly answered "no" to authorisation, OR
     * when they haven't answered AND haven't picked any expected track.
     * Track is set but authorisation null → not blocked (e.g. a candidate
     * volunteers STEM_OPT but skipped the yes/no — better to route them so
     * the I-983/E-Verify pieces appear; HR will surface the missing answer).
     */
    private boolean isBlocked(Boolean authorized, WorkAuthTrack track) {
        if (Boolean.FALSE.equals(authorized)) return true;
        return authorized == null && track == null;
    }

    private List<String> trackSpecificTaskKeys(WorkAuthTrack track) {
        List<String> keys = new ArrayList<>();
        if (track == WorkAuthTrack.STEM_OPT) {
            keys.add(TASK_I983_DRAFT);
            keys.add(TASK_I983_DSO_APPROVAL);
            keys.add(TASK_EVERIFY_BY_DAY_3);
        } else if (track == WorkAuthTrack.CPT) {
            keys.add(TASK_CPT_I20_VERIFY);
            // No I-983, no E-Verify by default for CPT.
        }
        // OPT, OTHER, and null-track + authorized true: I-9 only (already in
        // the standard onboarding seed — no extras here).

        // E-Verify for non-STEM only when the operator opts in.
        if (everifyEnabledForNonStem
                && track != WorkAuthTrack.STEM_OPT
                && !keys.contains(TASK_EVERIFY_BY_DAY_3)) {
            keys.add(TASK_EVERIFY_BY_DAY_3);
        }
        return keys;
    }

    // ── Phase 3 step 9 — compliance gate ────────────────────────────────────

    /**
     * Per-track "is this engagement ready to start?" check. Returns
     * {@code true} when every required compliance item is in its "done"
     * terminal state for the engagement's track snapshot:
     *
     *   - I-9 always required → {@link I9Status#COMPLETED}
     *   - I-983 required for STEM_OPT → {@link I983Status#DSO_APPROVED}
     *   - E-Verify required for STEM_OPT (and non-STEM when
     *     {@code everifyEnabledForNonStem}) → {@link EVerifyStatus#EMPLOYMENT_AUTHORIZED}
     *   - CPT_I20_VERIFY onboarding task complete for CPT
     *
     * Engagements in terminal states (TERMINATED / BLOCKED_NO_AUTHORIZATION)
     * return false — they shouldn't advance regardless of compliance state.
     */
    public boolean requirementsSatisfied(Engagement engagement) {
        return missingRequirements(engagement).isEmpty();
    }

    /**
     * Companion to {@link #requirementsSatisfied}. Returns a human-friendly
     * list of what's missing so the HR "mark ready" 400 response can name the
     * blockers ("Not ready: I-9 incomplete, E-Verify pending").
     */
    public List<String> missingRequirements(Engagement engagement) {
        List<String> missing = new ArrayList<>();
        if (engagement == null || engagement.getCandidate() == null) {
            missing.add("Engagement or candidate missing");
            return missing;
        }
        if (engagement.getStatus() == EngagementStatus.TERMINATED
                || engagement.getStatus() == EngagementStatus.BLOCKED_NO_AUTHORIZATION) {
            missing.add("Engagement is in a terminal state");
            return missing;
        }

        WorkAuthTrack track = engagement.getTrack();
        UUID candidateId = engagement.getCandidate().getId();
        UUID offerId = engagement.getOffer() != null ? engagement.getOffer().getId() : null;

        // I-9 always required.
        var i9 = i9FormRepository.findByCandidateId(candidateId).orElse(null);
        if (i9 == null || i9.getStatus() != I9Status.COMPLETED) {
            missing.add("I-9 incomplete");
        }

        // E-Verify: required for STEM, and non-STEM when the operator opts in.
        // Reached via the I-9 (case is 1:1 with the I-9 row).
        boolean everifyRequired = track == WorkAuthTrack.STEM_OPT
                || (everifyEnabledForNonStem && track != null);
        if (everifyRequired) {
            var everify = i9 != null
                    ? everifyCaseRepository.findByI9FormId(i9.getId()).orElse(null)
                    : null;
            if (everify == null || everify.getStatus() != EVerifyStatus.EMPLOYMENT_AUTHORIZED) {
                missing.add("E-Verify pending");
            }
        }

        // I-983 required for STEM only. A candidate may have multiple plans
        // (amendments) — any plan in DSO_APPROVED satisfies the gate.
        if (track == WorkAuthTrack.STEM_OPT) {
            boolean hasApproved = i983PlanRepository
                    .findByCandidateIdOrderByCreatedAtDesc(candidateId).stream()
                    .anyMatch(p -> p.getStatus() == I983Status.DSO_APPROVED);
            if (!hasApproved) {
                missing.add("I-983 not yet DSO-approved");
            }
        }

        // CPT-specific: I-20 CPT authorization must be confirmed via the
        // onboarding task seeded by the router. Without an offer link we can't
        // resolve the task uniquely, so we flag it as missing.
        if (track == WorkAuthTrack.CPT) {
            boolean cptVerified = false;
            if (offerId != null) {
                cptVerified = onboardingTaskRepository
                        .findByCandidateIdAndTaskKeyAndOfferId(candidateId, TASK_CPT_I20_VERIFY, offerId)
                        .map(t -> t.getStatus() == OnboardingTaskStatus.COMPLETED)
                        .orElse(false);
            }
            if (!cptVerified) {
                missing.add("CPT I-20 verification pending");
            }
        }

        return missing;
    }
}
