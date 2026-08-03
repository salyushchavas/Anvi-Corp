package com.anvicorp.api.erm.exit;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** ERM Phase 7 — Exit operations HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/exits")
@RequiredArgsConstructor
public class ErmExitController {

    private final ErmExitService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmExitDtos.ErmExitListPage list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.list(state, search, scope, caller, page, pageSize,
                MonthRange.parse(month));
    }

    @GetMapping("/ready")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmExitDtos.ReadyToExitListPage listReady(
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.listReady(scope, caller, page, pageSize,
                MonthRange.parse(month));
    }

    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmExitDtos.ReasonCodeGroup> reasonCodes() {
        return service.listReasonCodes();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN', 'MANAGER')")
    public ErmExitDtos.ErmExitDetail get(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return service.getDetail(id, caller);
    }

    @GetMapping("/{id}/feedback")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN', 'MANAGER')")
    public ResponseEntity<ErmExitDtos.FeedbackView> getFeedback(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return service.getFeedback(id, caller)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/initiate")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmExitDtos.ErmExitDetail initiate(
            @RequestBody ErmExitDtos.InitiateExitRequest req,
            @AuthenticationPrincipal User caller) {
        return service.initiate(req, caller);
    }

    @PostMapping("/{id}/checklist/{itemKey}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmExitDtos.ErmExitDetail updateChecklist(
            @PathVariable UUID id,
            @PathVariable String itemKey,
            @RequestBody ErmExitDtos.ChecklistUpdateRequest req,
            @AuthenticationPrincipal User caller) {
        return service.updateChecklistItem(id, itemKey, req, caller);
    }

    @PostMapping("/{id}/link-evaluation")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmExitDtos.ErmExitDetail linkEvaluation(
            @PathVariable UUID id,
            @RequestBody ErmExitDtos.LinkEvaluationRequest req,
            @AuthenticationPrincipal User caller) {
        return service.linkFinalEvaluation(id, req, caller);
    }

    @PostMapping("/{id}/assets")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmExitDtos.ErmExitDetail recordAssets(
            @PathVariable UUID id,
            @RequestBody ErmExitDtos.AssetStatusRequest req,
            @AuthenticationPrincipal User caller) {
        return service.recordAssetStatus(id, req, caller);
    }

    @PostMapping("/{id}/manager-override")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ErmExitDtos.ErmExitDetail managerOverride(
            @PathVariable UUID id,
            @RequestBody ErmExitDtos.ManagerOverrideRequest req,
            @AuthenticationPrincipal User caller) {
        return service.managerOverride(id, req, caller);
    }

    @PostMapping("/{id}/retry-revocation")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmExitDtos.ErmExitDetail retryRevocation(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return service.retryRevocation(id, caller);
    }

    @PostMapping("/{id}/internal-note")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Void> internalNote(
            @PathVariable UUID id,
            @RequestBody ErmExitDtos.InternalNoteRequest req,
            @AuthenticationPrincipal User caller) {
        service.appendInternalNote(id,
                req != null ? req.note() : null, caller);
        return ResponseEntity.noContent().build();
    }
}
