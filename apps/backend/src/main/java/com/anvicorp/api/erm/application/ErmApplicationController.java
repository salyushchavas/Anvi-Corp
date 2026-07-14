package com.anvicorp.api.erm.application;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 2 — application inbox + 4-decision flow. All endpoints
 * gated to ERM + SUPER_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/erm/applications")
@RequiredArgsConstructor
public class ErmApplicationController {

    private final ErmApplicationService ermApplicationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmApplicationDtos.ErmApplicationListPage list(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String jobId,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String workAuthType,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "mine") String scope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        ErmApplicationDtos.InboxFilters filters = new ErmApplicationDtos.InboxFilters(
                parseStages(stage),
                parseUuids(jobId),
                jobType,
                parseCsv(workAuthType),
                dateFrom, dateTo, search, scope);
        return ermApplicationService.list(filters, caller, page, pageSize);
    }

    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmApplicationDtos.ReasonCodeGroup> reasonCodes(
            @RequestParam(required = false) String decision) {
        return ermApplicationService.listReasonCodesForDecision(decision);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmApplicationDtos.ErmApplicationDetail detail(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return ermApplicationService.getDetail(id, caller);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmApplicationDtos.ErmApplicationDetail decide(
            @PathVariable UUID id,
            @RequestBody ErmApplicationDtos.ErmDecisionRequest req,
            @AuthenticationPrincipal User caller) {
        return ermApplicationService.decide(id, req, caller);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmApplicationDtos.ErmApplicationDetail resume(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return ermApplicationService.resumeFromHold(id, caller);
    }

    @PostMapping("/{id}/assign-owner")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Void> assignOwner(
            @PathVariable UUID id,
            @RequestBody ErmApplicationDtos.AssignOwnerRequest req,
            @AuthenticationPrincipal User caller) {
        ermApplicationService.assignOwner(id, req != null ? req.ermUserId() : null, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/internal-note")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> internalNote(
            @PathVariable UUID id,
            @RequestBody ErmApplicationDtos.InternalNoteRequest req,
            @AuthenticationPrincipal User caller) {
        ermApplicationService.appendInternalNote(id, req != null ? req.note() : null, caller);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/bulk-decision")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmApplicationDtos.BulkDecisionResult bulkDecision(
            @RequestBody ErmApplicationDtos.BulkDecisionRequest req,
            @AuthenticationPrincipal User caller) {
        return ermApplicationService.bulkDecide(req, caller);
    }

    private static List<ApplicationStatus> parseStages(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<ApplicationStatus> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            try { out.add(ApplicationStatus.valueOf(s.trim().toUpperCase())); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private static List<UUID> parseUuids(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<UUID> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            try { out.add(UUID.fromString(s.trim())); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
