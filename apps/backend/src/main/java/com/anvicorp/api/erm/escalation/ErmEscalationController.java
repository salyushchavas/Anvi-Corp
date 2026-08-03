package com.anvicorp.api.erm.escalation;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** ERM Phase 6 — Escalations HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/escalations")
@RequiredArgsConstructor
public class ErmEscalationController {

    private final ErmEscalationService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionListPage list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> severity,
            @RequestParam(name = "exceptionType", required = false) List<String> exceptionType,
            @RequestParam(required = false) UUID assignedToId,
            @RequestParam(required = false) UUID internLifecycleId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "all") String scope,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.list(status, severity, exceptionType, assignedToId,
                internLifecycleId, search, scope, caller, page, pageSize,
                MonthRange.parse(month));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail assign(
            @PathVariable UUID id,
            @RequestBody ErmEscalationDtos.AssignRequest req,
            @AuthenticationPrincipal User caller) {
        return service.assign(id, req, caller);
    }

    @PostMapping("/{id}/in-progress")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail markInProgress(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return service.markInProgress(id, caller);
    }

    @PostMapping("/{id}/note")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail note(
            @PathVariable UUID id,
            @RequestBody ErmEscalationDtos.NoteRequest req,
            @AuthenticationPrincipal User caller) {
        return service.addNote(id, req, caller);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail resolve(
            @PathVariable UUID id,
            @RequestBody ErmEscalationDtos.ResolutionRequest req,
            @AuthenticationPrincipal User caller) {
        return service.resolve(id, req, caller);
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail dismiss(
            @PathVariable UUID id,
            @RequestBody ErmEscalationDtos.DismissalRequest req,
            @AuthenticationPrincipal User caller) {
        return service.dismiss(id, req, caller);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail reopen(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return service.reopen(id, caller);
    }

    @PostMapping("/bulk-resolve")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.BulkActionResult bulkResolve(
            @RequestBody ErmEscalationDtos.BulkResolveRequest req,
            @AuthenticationPrincipal User caller) {
        return service.bulkResolve(req, caller);
    }

    @PostMapping("/bulk-dismiss")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmEscalationDtos.BulkActionResult bulkDismiss(
            @RequestBody ErmEscalationDtos.BulkDismissRequest req,
            @AuthenticationPrincipal User caller) {
        return service.bulkDismiss(req, caller);
    }

    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmEscalationDtos.ReasonCodeGroup> reasonCodes() {
        return service.listReasonCodes();
    }
}
