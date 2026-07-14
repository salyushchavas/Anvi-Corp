package com.anvicorp.api.controller;

import com.anvicorp.api.dto.engagement.EngagementResponse;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.ComplianceRoutingService;
import com.anvicorp.api.service.EngagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 step 9 — engagement read + HR transition actions.
 *
 *   GET  /api/v1/engagements/{id}            (HR/ERM/ADMIN read)
 *   POST /api/v1/engagements/{id}/mark-ready (HR/ERM/ADMIN — gates on requirements)
 *   POST /api/v1/engagements/{id}/start      (HR/ERM/ADMIN — manual READY → ACTIVE)
 *
 * The list of "missing requirements" is computed by
 * {@link ComplianceRoutingService#missingRequirements} per the engagement's
 * track snapshot (I-9 / I-983 / E-Verify / CPT_I20_VERIFY).
 */
@RestController
@RequestMapping("/api/v1/engagements")
@RequiredArgsConstructor
public class EngagementController {

    private final EngagementRepository engagementRepository;
    private final EngagementService engagementService;
    private final ComplianceRoutingService complianceRoutingService;
    private final UserRepository userRepository;
    private final com.anvicorp.api.service.EngagementActivationService engagementActivationService;

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    @Transactional(readOnly = true)
    public EngagementResponse getOne(@PathVariable UUID id) {
        Engagement engagement = engagementRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Engagement not found: " + id));
        return toResponse(engagement);
    }

    /**
     * Activation-readiness signal used by HR/Ops UI to decide whether to
     * render the "Activate Engagement" button on a PENDING_COMPLIANCE row.
     * Pure read — never mutates. The actual transition still goes through
     * {@code POST /mark-ready} on this controller.
     */
    @GetMapping("/{id}/activation-readiness")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public ActivationReadinessResponse activationReadiness(@PathVariable UUID id) {
        Engagement engagement = engagementRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Engagement not found: " + id));
        boolean ready = engagementActivationService.isReady(engagement);
        var missing = complianceRoutingService.missingRequirements(engagement);
        return new ActivationReadinessResponse(ready, missing);
    }

    /**
     * List of PENDING_COMPLIANCE engagements that are READY to activate,
     * scoped for HR's landing dashboard. Pure read — every row carries
     * the same minimal payload the operations onboarding queue uses so the
     * frontend can render the inline "Activate Engagement" button.
     */
    @GetMapping("/awaiting-activation")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public java.util.List<AwaitingActivationRow> awaitingActivation() {
        java.util.List<Engagement> pending = engagementRepository
                .findByStatus(com.anvicorp.api.enums.EngagementStatus.PENDING_COMPLIANCE);
        java.util.List<AwaitingActivationRow> rows = new java.util.ArrayList<>();
        for (Engagement e : pending) {
            if (!engagementActivationService.isReady(e)) continue;
            var candidate = e.getCandidate();
            var candidateUser = candidate != null ? candidate.getUser() : null;
            var application = e.getApplication();
            var posting = application != null ? application.getJobPosting() : null;
            rows.add(new AwaitingActivationRow(
                    e.getId(),
                    candidate != null ? candidate.getId() : null,
                    candidateUser != null ? candidateUser.getFullName() : null,
                    posting != null ? posting.getTitle() : null
            ));
        }
        return rows;
    }

    /** Lightweight readiness DTO inlined here — no other surface needs it. */
    public record ActivationReadinessResponse(
            boolean ready,
            java.util.List<String> missing) {}

    /** Minimal row payload for the HR / Ops awaiting-activation list. */
    public record AwaitingActivationRow(
            UUID engagementId,
            UUID candidateId,
            String candidateName,
            String position) {}

    // ── TEMP: bug-#4 recovery surface ──────────────────────────────────────────
    // Listing + bypass action used by the HR landing's "Stuck engagements"
    // card. The proper flow is /awaiting-activation + /mark-ready; this
    // pair is for engagements that landed stuck while the activation gate
    // had its bug. Once Bug #4 is verified working in production, every
    // [TEMP-FORCE-HIRE] block in this file should be removed.

    /**
     * TEMP: returns ALL engagements at PENDING_COMPLIANCE (regardless of
     * compliance readiness) so HR can see and force-advance stuck rows.
     * Caller-side ready flag indicates whether the proper /mark-ready
     * flow is also available for that row.
     */
    // TEMP: remove after Bug #4 production fix verified
    @GetMapping("/pending-compliance")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public java.util.List<PendingComplianceRow> pendingCompliance() {
        java.util.List<Engagement> pending = engagementRepository
                .findByStatus(com.anvicorp.api.enums.EngagementStatus.PENDING_COMPLIANCE);
        java.util.List<PendingComplianceRow> rows = new java.util.ArrayList<>();
        for (Engagement e : pending) {
            var candidate = e.getCandidate();
            var candidateUser = candidate != null ? candidate.getUser() : null;
            var application = e.getApplication();
            var posting = application != null ? application.getJobPosting() : null;
            rows.add(new PendingComplianceRow(
                    e.getId(),
                    candidate != null ? candidate.getId() : null,
                    candidateUser != null ? candidateUser.getFullName() : null,
                    posting != null ? posting.getTitle() : null,
                    engagementActivationService.isReady(e)
            ));
        }
        return rows;
    }

    /** TEMP: row payload for the HR stuck-engagements card. */
    // TEMP: remove after Bug #4 production fix verified
    public record PendingComplianceRow(
            UUID engagementId,
            UUID candidateId,
            String candidateName,
            String position,
            boolean ready) {}

    /**
     * TEMP: force-flip PENDING_COMPLIANCE → READY_TO_START bypassing the
     * compliance readiness gate. Used by HR to unstick engagements that
     * landed in PENDING_COMPLIANCE while the activation gate had its
     * Bug #4 defect. Logs are tagged [TEMP-FORCE-HIRE] so the whole
     * surface can be removed by grep later.
     */
    // TEMP: remove after Bug #4 production fix verified
    @PostMapping("/{id}/force-mark-hired")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<EngagementResponse> forceMarkHired(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        Engagement engagement = engagementRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Engagement not found: " + id));
        if (engagement.getStatus()
                != com.anvicorp.api.enums.EngagementStatus.PENDING_COMPLIANCE) {
            throw new BadRequestException("Engagement is not PENDING_COMPLIANCE");
        }
        UUID actorId = caller != null ? caller.getId() : null;
        UUID candidateId = engagement.getCandidate() != null
                ? engagement.getCandidate().getId() : null;
        org.slf4j.LoggerFactory.getLogger(EngagementController.class).warn(
                "[TEMP-FORCE-HIRE] HR user {} force-advanced engagement {} "
                        + "(candidate {}) PENDING_COMPLIANCE → READY_TO_START",
                actorId, engagement.getId(), candidateId);
        Engagement updated = engagementService.transitionToSystem(
                engagement,
                com.anvicorp.api.enums.EngagementStatus.READY_TO_START,
                "FORCE_MARK_HIRED_TEMP",
                actorId);
        return ResponseEntity.ok(toResponse(updated));
    }

    // ── /TEMP ──────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/mark-ready")
    @PreAuthorize("hasRole('ERM')")
    @Transactional
    public ResponseEntity<EngagementResponse> markReady(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        Engagement engagement = engagementRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Engagement not found: " + id));
        Engagement updated = engagementService.markReady(engagement, complianceRoutingService, caller);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('ERM')")
    @Transactional
    public ResponseEntity<EngagementResponse> start(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        Engagement engagement = engagementRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Engagement not found: " + id));
        Engagement updated = engagementService.startEngagement(engagement, caller);
        return ResponseEntity.ok(toResponse(updated));
    }

    /**
     * Two-role workflow prerequisite — assign the Reporting Manager who
     * runs the post-merge viva and signs final project completion. Pass
     * {@code reportingManagerUserId: null} to unassign. The target user
     * MUST hold the {@code REPORTING_MANAGER} role.
     */
    @PatchMapping("/{id}/reporting-manager")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<EngagementResponse> setReportingManager(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        Engagement engagement = engagementRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Engagement not found: " + id));
        String raw = body != null ? body.get("reportingManagerUserId") : null;
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            engagement.setReportingManager(null);
        } else {
            UUID userId;
            try {
                userId = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Malformed reportingManagerUserId");
            }
            User user = userRepository.findById(userId).orElseThrow(
                    () -> new ResourceNotFoundException("User not found: " + userId));
            if (user.getRoles() == null
                    || !user.getRoles().contains(UserRole.REPORTING_MANAGER)) {
                throw new BadRequestException(
                        "User " + userId + " does not hold the REPORTING_MANAGER role.");
            }
            engagement.setReportingManager(user);
        }
        engagementRepository.save(engagement);
        return ResponseEntity.ok(toResponse(engagement));
    }

    private EngagementResponse toResponse(Engagement e) {
        var missing = complianceRoutingService.missingRequirements(e);
        var candidate = e.getCandidate();
        var candidateUser = candidate != null ? candidate.getUser() : null;
        var application = e.getApplication();
        var posting = application != null ? application.getJobPosting() : null;
        var supervisor = e.getSupervisor();
        return EngagementResponse.builder()
                .id(e.getId())
                .applicationId(application != null ? application.getId() : null)
                .candidateId(candidate != null ? candidate.getId() : null)
                .offerId(e.getOffer() != null ? e.getOffer().getId() : null)
                .entityId(e.getEntity() != null ? e.getEntity().getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .entityName(e.getEntity() != null ? e.getEntity().getName() : null)
                .jobPostingTitle(posting != null ? posting.getTitle() : null)
                .track(e.getTrack())
                .status(e.getStatus())
                .plannedStartDate(e.getPlannedStartDate())
                .plannedEndDate(e.getPlannedEndDate())
                .actualStartDate(e.getActualStartDate())
                .actualEndDate(e.getActualEndDate())
                .supervisorId(supervisor != null ? supervisor.getId() : null)
                .supervisorName(supervisor != null ? supervisor.getFullName() : null)
                .worksite(e.getWorksite())
                .hoursPerWeek(e.getHoursPerWeek())
                .missingRequirements(missing)
                .readyToStart(missing.isEmpty())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
