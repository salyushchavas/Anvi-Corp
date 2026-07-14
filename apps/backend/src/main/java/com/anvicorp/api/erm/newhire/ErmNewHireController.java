package com.anvicorp.api.erm.newhire;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.offer.ErmOfferDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** ERM Phase 4 — Prospective New Hire List HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/new-hire")
@RequiredArgsConstructor
public class ErmNewHireController {

    private final ErmNewHireService ermNewHireService;
    private final OnboardingTrackerService onboardingTracker;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.NewHireListPage list(
            @RequestParam(required = false, defaultValue = "pending") String tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return ermNewHireService.list(tab, caller, page, pageSize);
    }

    /**
     * ERM Phase 8.2 — convenience endpoint for the default Pending
     * Document Assignment tab. Equivalent to
     * {@code GET /?tab=pending-document-assignment}.
     */
    @GetMapping("/pending-document-assignment")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.NewHireListPage pendingDocumentAssignment(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return ermNewHireService.list("pending-document-assignment",
                caller, page, pageSize);
    }

    @GetMapping("/in-progress")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.NewHireListPage inProgress(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return ermNewHireService.list("in-progress", caller, page, pageSize);
    }

    @GetMapping("/{lifecycleId}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.NewHireDetail detail(@PathVariable UUID lifecycleId) {
        return ermNewHireService.detail(lifecycleId);
    }

    @PostMapping("/{lifecycleId}/assign-reporting")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.NewHireDetail assignReporting(
            @PathVariable UUID lifecycleId,
            @RequestBody ErmOfferDtos.AssignReportingRequest req,
            @AuthenticationPrincipal User caller) {
        return ermNewHireService.assignReportingStructure(lifecycleId, req, caller);
    }

    /** Phase 8.6.4 — inline Manager assignment from the New Hire detail
     *  page. Non-blocking: Manager can be set / changed / cleared at any
     *  lifecycle point. Doesn't touch reporting_structure_complete. */
    @PatchMapping("/{lifecycleId}/manager")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.NewHireDetail assignManager(
            @PathVariable UUID lifecycleId,
            @RequestBody ErmOfferDtos.AssignManagerRequest req,
            @AuthenticationPrincipal User caller) {
        return ermNewHireService.assignManager(
                lifecycleId, req != null ? req.managerUserId() : null, caller);
    }

    @PostMapping("/{lifecycleId}/update-start-date")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.NewHireDetail updateStartDate(
            @PathVariable UUID lifecycleId,
            @RequestBody ErmOfferDtos.UpdateStartDateRequest req,
            @AuthenticationPrincipal User caller) {
        return ermNewHireService.updateTentativeStartDate(lifecycleId,
                req != null ? req.newDate() : null, caller);
    }

    @GetMapping("/eligible-trainers")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmOfferDtos.UserStub> eligibleTrainers() {
        return ermNewHireService.listEligible(UserRole.TRAINER);
    }

    @GetMapping("/eligible-evaluators")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmOfferDtos.UserStub> eligibleEvaluators() {
        // Admin can create staff with EITHER UserRole.EVALUATOR or
        // UserRole.REPORTING_MANAGER (historical overlap for the
        // evaluator function). Union both so admin-created accounts
        // show up regardless of which role was picked. Counting hint
        // stays on REPORTING_MANAGER because the per-intern column is
        // lifecycle.evaluator_id, which the rest of the codebase still
        // stamps based on the REPORTING_MANAGER assignment slot.
        return ermNewHireService.listEligibleAny(
                java.util.EnumSet.of(UserRole.EVALUATOR, UserRole.REPORTING_MANAGER),
                UserRole.REPORTING_MANAGER);
    }

    @GetMapping("/eligible-managers")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmOfferDtos.UserStub> eligibleManagers() {
        return ermNewHireService.listEligible(UserRole.MANAGER);
    }

    // ── Onboarding tracker (gated selected→active flow) ────────────────────

    /**
     * 6-step tracker payload for the ERM intern detail page. Steps + the
     * single CURRENT step + canActivate are all server-authoritative so
     * the frontend can't accidentally drift out of sync with the gating
     * rules.
     */
    @GetMapping("/{lifecycleId}/onboarding-tracker")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public OnboardingTrackerDtos.OnboardingTracker onboardingTracker(
            @PathVariable UUID lifecycleId) {
        return onboardingTracker.compute(lifecycleId);
    }

    /**
     * Step 4 action — fire the "new intern joined" notification to the
     * (singleton) trainer + manager, stamp {@code team_notified_at}, and
     * return the recomputed tracker so the frontend can refresh in-place.
     */
    @PostMapping("/{lifecycleId}/notify-team")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public OnboardingTrackerDtos.OnboardingTracker notifyTeam(
            @PathVariable UUID lifecycleId,
            @AuthenticationPrincipal User caller) {
        return onboardingTracker.notifyTeam(lifecycleId, caller);
    }

    /**
     * Step 2 action — nudge the intern that their offer is awaiting
     * signature. Lightweight: dispatches an in-app + branded email
     * reminder; doesn't touch offer state.
     */
    @PostMapping("/{lifecycleId}/signature-reminder")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public void signatureReminder(
            @PathVariable UUID lifecycleId,
            @AuthenticationPrincipal User caller) {
        onboardingTracker.sendSignatureReminder(lifecycleId, caller);
    }
}
