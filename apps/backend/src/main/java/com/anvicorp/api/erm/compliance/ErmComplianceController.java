package com.anvicorp.api.erm.compliance;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** ERM Phase 5 — Compliance Tracker HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/compliance")
@RequiredArgsConstructor
public class ErmComplianceController {

    private final ErmComplianceService service;

    @GetMapping("/pipeline")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmComplianceDtos.PipelinePage pipeline(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        return service.listPipeline(filter, search, page, pageSize);
    }

    @GetMapping("/interns/{userId}/timeline")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmComplianceDtos.InternTimeline timeline(@PathVariable UUID userId) {
        return service.getInternTimeline(userId);
    }

    @PostMapping("/interns/{userId}/work-auth")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmComplianceDtos.WorkAuthCard updateWorkAuth(
            @PathVariable UUID userId,
            @RequestBody ErmComplianceDtos.UpdateWorkAuthRequest req,
            @AuthenticationPrincipal User caller) {
        return service.updateWorkAuth(userId, req, caller);
    }

    @PostMapping("/interns/{userId}/i9-section2")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmComplianceDtos.I9TimelineCard recordI9Section2(
            @PathVariable UUID userId,
            @RequestBody ErmComplianceDtos.RecordI9Section2Request req,
            @AuthenticationPrincipal User caller) {
        return service.recordI9Section2(userId, req, caller);
    }

    @PostMapping("/everify-cases")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmComplianceDtos.EverifyCard recordEverifyCase(
            @RequestBody ErmComplianceDtos.RecordEverifyRequest req,
            @AuthenticationPrincipal User caller) {
        return service.recordEverifyCase(req, caller);
    }

    @PostMapping("/everify-cases/{caseId}/status")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmComplianceDtos.EverifyCard updateEverifyStatus(
            @PathVariable UUID caseId,
            @RequestBody ErmComplianceDtos.UpdateEverifyStatusRequest req,
            @AuthenticationPrincipal User caller) {
        return service.updateEverifyStatus(caseId, req, caller);
    }

    @PostMapping("/everify-cases/{caseId}/reveal-number")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmComplianceDtos.RevealCaseNumberResponse revealCaseNumber(
            @PathVariable UUID caseId,
            @AuthenticationPrincipal User caller) {
        return service.revealEverifyCaseNumber(caseId, caller);
    }
}
