package com.anvicorp.api.controller;

import com.anvicorp.api.dto.ApplicationCreateRequest;
import com.anvicorp.api.dto.ApplicationResponse;
import com.anvicorp.api.dto.ApplicationStatusUpdateRequest;
import com.anvicorp.api.dto.BulkApplicationActionRequest;
import com.anvicorp.api.dto.BulkApplicationActionResponse;
import com.anvicorp.api.dto.RecruiterDecisionRequest;
import com.anvicorp.api.dto.candidate.ApplicationJourneyResponse;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.service.ApplicationService;
import com.anvicorp.api.service.CandidateApplicationsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;
    private final CandidateApplicationsService candidateApplicationsService;

    @PostMapping
    @PreAuthorize("hasRole('INTERN')")
    public ResponseEntity<ApplicationResponse> apply(
            @Valid @RequestBody ApplicationCreateRequest req,
            @AuthenticationPrincipal User user) {
        ApplicationResponse created = applicationService.apply(user, req);
        return ResponseEntity.created(URI.create("/api/v1/applications/" + created.getId()))
                .body(created);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public List<ApplicationResponse> listMine(@AuthenticationPrincipal User user) {
        return applicationService.listForCandidate(user);
    }

    /**
     * Phase 2 — doc-spec alias for {@code /me}. Both endpoints stay live so
     * legacy callers continue to work; new intern frontend uses {@code /mine}.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('INTERN')")
    public List<ApplicationResponse> listMineAlias(@AuthenticationPrincipal User user) {
        return applicationService.listForCandidate(user);
    }

    /**
     * Phase 2 — applicant-initiated withdrawal. Allowed only at APPLIED or
     * SHORTLISTED; ApplicationService enforces ownership + stage guard.
     */
    @PatchMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('INTERN')")
    public ApplicationResponse withdraw(@PathVariable UUID id,
                                        @AuthenticationPrincipal User caller) {
        return applicationService.withdraw(id, caller);
    }

    /**
     * Intern-initiated selection acknowledgment. Recorded after the ERM
     * marks the candidate SELECTED on the interview; the intern clicks
     * "Receive my offer letter" on the dashboard which fires this. The
     * service enforces (a) caller-is-applicant ownership and (b) the
     * latest completed interview decision is SELECTED. Idempotent.
     */
    @PostMapping("/{id}/acknowledge-selection")
    @PreAuthorize("hasRole('INTERN')")
    public ApplicationResponse acknowledgeSelection(@PathVariable UUID id,
                                                     @AuthenticationPrincipal User caller) {
        return applicationService.acknowledgeSelection(id, caller);
    }

    /**
     * Richer per-application journey for the candidate's My Applications page.
     * Same source-of-truth as {@code /me}, plus interview/offer/audit-derived
     * stage dates and an action-needed CTA when something is pending.
     */
    @GetMapping("/me/journey")
    @PreAuthorize("hasRole('INTERN')")
    public List<ApplicationJourneyResponse> listMyJourney(@AuthenticationPrincipal User user) {
        return candidateApplicationsService.listJourneyForCandidate(user);
    }

    /**
     * Staff applications list. Supports:
     *   - search   substring (case-insensitive) on candidate fullName OR email
     *   - status   accepts multiple (?status=APPLIED&status=SHORTLISTED)
     *   - entityId / jobPostingId narrow the result set
     *   - sort     "appliedAt|status|candidateName,asc|desc" (default appliedAt,desc)
     *   - page / size (size capped at 100)
     */
    @GetMapping
    @PreAuthorize("hasRole('ERM')")
    public PagedResponse<ApplicationResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<ApplicationStatus> status,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID jobPostingId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                parseSort(sort));
        return PagedResponse.of(
                applicationService.search(search, status, entityId, jobPostingId, pageable));
    }

    /**
     * Whitelist-driven sort parsing — keeps SQL safe against unexpected
     * property names. Format: "field,direction" (direction optional, defaults
     * to ASC). Unknown fields fall back to the original default.
     */
    private static Sort parseSort(String raw) {
        Sort defaultSort = Sort.by(Sort.Direction.DESC, "appliedAt");
        if (raw == null || raw.isBlank()) return defaultSort;
        String[] parts = raw.split(",", 2);
        String field = parts[0].trim();
        Sort.Direction dir = (parts.length > 1
                && "desc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        String mapped = switch (field) {
            case "appliedAt" -> "appliedAt";
            case "status" -> "status";
            // Spring Data resolves dotted paths against the entity graph; the
            // Specification's joins make this column reachable.
            case "candidateName" -> "candidate.user.fullName";
            default -> null;
        };
        if (mapped == null) return defaultSort;
        return Sort.by(dir, mapped);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'TRAINER')")
    public ApplicationResponse getOne(@PathVariable UUID id,
                                      @AuthenticationPrincipal User user) {
        // CANDIDATE is gated to their own application in ApplicationService.findById;
        // this controller guard is defense-in-depth so an unauthenticated request
        // is rejected before the service runs.
        return applicationService.findById(id, user);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ERM')")
    public ApplicationResponse updateStatus(@PathVariable UUID id,
                                            @Valid @RequestBody ApplicationStatusUpdateRequest req,
                                            @AuthenticationPrincipal User user) {
        return applicationService.updateStatus(id, req, user);
    }

    /**
     * One-click Shortlist from the recruiter review screen. Accepts an optional
     * {rating, note} body; persists those alongside the status transition. Routes
     * through the same status-transition service as the Kanban drag; adds a
     * SHORTLIST audit entry. Idempotent — returns 200 even if already SHORTLISTED.
     */
    @PostMapping("/{id}/shortlist")
    @PreAuthorize("hasRole('ERM')")
    public ApplicationResponse shortlist(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) RecruiterDecisionRequest req,
            @AuthenticationPrincipal User user) {
        return applicationService.shortlist(id, req, user);
    }

    /** Mirror of {@link #shortlist} for one-click rejection. Adds a REJECT audit entry. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ERM')")
    public ApplicationResponse reject(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) RecruiterDecisionRequest req,
            @AuthenticationPrincipal User user) {
        return applicationService.reject(id, req, user);
    }

    /**
     * Phase 2.3 — staff send the conditional employment confirmation off the
     * interview scorecard. Body is empty; the recruiter's identity comes from
     * the principal. Idempotent: re-clicking on an already-selected app is a
     * no-op (no duplicate audit, no duplicate stub email).
     */
    @PostMapping("/{id}/conditional-select")
    @PreAuthorize("hasRole('ERM')")
    public ApplicationResponse conditionalSelect(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return applicationService.conditionalSelect(id, user);
    }

    /**
     * Bulk shortlist/reject for the data-table view. Idempotent — applications
     * already at the target status are counted as skipped, not failures.
     * Returns {@code { updated, skipped }} so the UI can render an honest toast.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ERM')")
    public BulkApplicationActionResponse bulkAction(
            @Valid @RequestBody BulkApplicationActionRequest req,
            @AuthenticationPrincipal User user) {
        return applicationService.bulkAction(req, user);
    }

    /**
     * ERM Phase 2 — intern closes the loop on INFO_REQUESTED by providing
     * the fields ERM asked for. Stage transitions INFO_REQUESTED → APPLIED;
     * ERM owner notified via in-app dispatch.
     */
    @PostMapping("/{id}/provide-info")
    @PreAuthorize("hasRole('INTERN')")
    public ApplicationResponse provideInfo(
            @PathVariable UUID id,
            @RequestBody com.anvicorp.api.erm.application.ErmApplicationDtos.ProvideInfoRequest req,
            @AuthenticationPrincipal User caller) {
        return applicationService.provideInfo(id, req, caller);
    }
}
