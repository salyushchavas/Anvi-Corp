package com.anvicorp.api.service;

import com.anvicorp.api.entity.Engagement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Thin readiness oracle for the {@code PENDING_COMPLIANCE → READY_TO_START}
 * transition. Single public method: {@link #isReady(Engagement)}, which
 * delegates to {@link ComplianceRoutingService#requirementsSatisfied}.
 *
 * <p><b>Pure read.</b> No state mutation, no event publishing, no audit
 * write. Called only by the engagement controller (drives the HR/Ops
 * "Activate Engagement" affordance) and the recovery backfill runner
 * (gates the one-time sweep). Compliance services must NEVER depend on
 * this bean — an earlier auto-advance attempt wired it into the
 * compliance write paths and produced a circular bean dependency that
 * broke startup.</p>
 */
@Service
@RequiredArgsConstructor
public class EngagementActivationService {

    private final ComplianceRoutingService complianceRoutingService;

    public boolean isReady(Engagement engagement) {
        if (engagement == null) return false;
        return complianceRoutingService.requirementsSatisfied(engagement);
    }
}
