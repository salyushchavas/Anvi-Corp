package com.anvicorp.api.manager.hire;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Manager Hire Approvals HTTP surface — org-wide queue (no per-intern
 * manager assignment at interview stage). Any MANAGER (or SUPER_ADMIN)
 * can list, view, and decide.
 */
@RestController
@RequestMapping("/api/v1/manager/hire-approvals")
@RequiredArgsConstructor
public class ManagerHireApprovalController {

    private final ManagerHireApprovalService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerHireApprovalDtos.HireApprovalListPage list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        return service.list(search, page, pageSize);
    }

    @GetMapping("/{interviewId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerHireApprovalDtos.HireApprovalDetail get(
            @PathVariable UUID interviewId) {
        return service.getDetail(interviewId);
    }

    @PostMapping("/{interviewId}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerHireApprovalDtos.HireApprovalDetail approve(
            @PathVariable UUID interviewId,
            @RequestBody(required = false) ManagerHireApprovalDtos.HireApprovalDecisionRequest req,
            @AuthenticationPrincipal User caller) {
        return service.approve(interviewId, req == null ? null : req.note(), caller);
    }

    @PostMapping("/{interviewId}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerHireApprovalDtos.HireApprovalDetail reject(
            @PathVariable UUID interviewId,
            @RequestBody(required = false) ManagerHireApprovalDtos.HireApprovalDecisionRequest req,
            @AuthenticationPrincipal User caller) {
        return service.reject(interviewId, req == null ? null : req.note(), caller);
    }

    /**
     * Park the hire decision (revisitable pause — no lifecycle advance,
     * no offer letter, no rejection email). The row stays visible in
     * the queue and can later transition to approve or reject.
     */
    @PostMapping("/{interviewId}/hold")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerHireApprovalDtos.HireApprovalDetail hold(
            @PathVariable UUID interviewId,
            @RequestBody(required = false) ManagerHireApprovalDtos.HireApprovalDecisionRequest req,
            @AuthenticationPrincipal User caller) {
        return service.hold(interviewId, req == null ? null : req.note(), caller);
    }
}
