package com.anvicorp.api.manager;

import com.anvicorp.api.dto.supervised.RejectTimesheetRequest;
import com.anvicorp.api.dto.supervised.TimesheetResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.escalation.ErmEscalationDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Manager Phase 1 — read-only HTTP surface for the Executive Overview
 *  and Applicant Pipeline. RBAC: MANAGER + SUPER_ADMIN (enforced at
 *  endpoint level, not relying on frontend gating). */
@RestController
@RequestMapping("/api/v1/manager")
@RequiredArgsConstructor
@Slf4j
public class ManagerController {

    private final ManagerDashboardService dashboardService;
    private final ManagerPipelineService pipelineService;
    private final ManagerOnboardingService onboardingService;
    private final ManagerActiveInternsService activeInternsService;
    private final ManagerTimesheetService managerTimesheetService;
    private final ManagerTimesheetApprovalService managerTimesheetApprovalService;
    private final ManagerRiskCenterService riskCenterService;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.OverviewResponse overview(
            @AuthenticationPrincipal User caller) {
        return dashboardService.getOverview(caller);
    }

    @GetMapping("/pipeline")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.PipelineResponse pipeline(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String technology,
            @RequestParam(required = false) UUID ermOwner,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return pipelineService.list(caller, stage, technology, ermOwner, search, page, pageSize);
    }

    @GetMapping("/pipeline/filters")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.FilterOptions pipelineFilters() {
        return pipelineService.filterOptions();
    }

    // ── Phase 2 — Onboarding Health ──────────────────────────────────────

    @GetMapping("/onboarding")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.OnboardingResponse onboarding(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String workAuthType,
            @RequestParam(required = false) UUID ermOwner,
            @RequestParam(required = false) String needsAttention,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return onboardingService.list(caller, stage, workAuthType, ermOwner,
                needsAttention, search, page, pageSize);
    }

    @GetMapping("/onboarding/summary")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.OnboardingSummary onboardingSummary(
            @AuthenticationPrincipal User caller) {
        return onboardingService.summary(caller);
    }

    @GetMapping("/onboarding/filters")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.OnboardingFilterOptions onboardingFilters() {
        return onboardingService.filterOptions();
    }

    // ── Phase 3A — Active Interns (read-only health view) ────────────────

    @GetMapping("/active-interns")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.ActiveInternResponse activeInterns(
            @RequestParam(required = false) String technology,
            @RequestParam(required = false) UUID trainerId,
            @RequestParam(required = false) UUID evaluatorId,
            @RequestParam(required = false) UUID ermOwner,
            @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) String health,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return activeInternsService.list(caller, technology, trainerId,
                evaluatorId, ermOwner, managerId, health, search,
                page, pageSize);
    }

    @GetMapping("/active-interns/summary")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.ActiveInternSummary activeInternsSummary(
            @AuthenticationPrincipal User caller) {
        return activeInternsService.summary(caller);
    }

    @GetMapping("/active-interns/filters")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.ActiveInternFilterOptions activeInternsFilters() {
        return activeInternsService.filterOptions();
    }

    // ── Phase 3B — Timesheet Approvals (first Manager write action) ──────

    @GetMapping("/timesheets")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.TimesheetListResponse timesheets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) UUID ermOwner,
            @RequestParam(required = false) String technology,
            @RequestParam(required = false) String weekStart,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return managerTimesheetService.list(caller, status, managerId, ermOwner,
                technology, weekStart, search, page, pageSize);
    }

    @GetMapping("/timesheets/filters")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.TimesheetFilterOptions timesheetsFilters() {
        return managerTimesheetService.filterOptions();
    }

    /** Ownership gate runs inside the service — caller must be the assigned
     *  manager OR SUPER_ADMIN. 403 otherwise; the existing TimesheetService
     *  state machine handles the actual SUBMITTED → APPROVED transition. */
    @PostMapping("/timesheets/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public TimesheetResponse approveTimesheet(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return managerTimesheetApprovalService.approve(id, caller);
    }

    @PostMapping("/timesheets/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public TimesheetResponse rejectTimesheet(
            @PathVariable UUID id,
            @Valid @RequestBody RejectTimesheetRequest req,
            @AuthenticationPrincipal User caller) {
        return managerTimesheetApprovalService.reject(id, req, caller);
    }

    /**
     * Phase B2 — batch-approve every VERIFIED row in the supplied id
     * list. Returns a per-id outcome map (APPROVED / FORBIDDEN / error
     * message). Wrong-state rows are skipped silently with their
     * server-side error captured in the response.
     */
    @PostMapping("/timesheets/approve-batch")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public java.util.Map<UUID, String> approveBatch(
            @Valid @RequestBody com.anvicorp.api.dto.supervised.TimesheetBatchRequest req,
            @AuthenticationPrincipal User caller) {
        return managerTimesheetApprovalService.approveBatch(req.ids(), caller);
    }

    // ── Phase 4A — Risk Center (aggregated exceptions + escalation) ──────

    @GetMapping("/risk-center")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.RiskListResponse riskCenter(
            @RequestParam(required = false) java.util.List<String> severity,
            @RequestParam(required = false) java.util.List<String> exceptionType,
            @RequestParam(required = false) java.util.List<String> status,
            @RequestParam(required = false) UUID assignedToId,
            @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return riskCenterService.list(caller, severity, exceptionType, status,
                assignedToId, managerId, search, page, pageSize);
    }

    @GetMapping("/risk-center/summary")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.RiskSummary riskCenterSummary(
            @AuthenticationPrincipal User caller) {
        return riskCenterService.summary(caller);
    }

    @GetMapping("/risk-center/filters")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerDtos.RiskFilterOptions riskCenterFilters() {
        return riskCenterService.filterOptions();
    }

    @GetMapping("/risk-center/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail riskCenterDetail(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return riskCenterService.get(id, caller);
    }

    @PostMapping("/risk-center/{id}/assign")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail riskAssign(
            @PathVariable UUID id,
            @RequestBody ManagerDtos.AssignRiskRequest req,
            @AuthenticationPrincipal User caller) {
        return riskCenterService.assign(id, req, caller);
    }

    @PostMapping("/risk-center/{id}/in-progress")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail riskInProgress(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return riskCenterService.markInProgress(id, caller);
    }

    @PostMapping("/risk-center/{id}/note")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail riskAddNote(
            @PathVariable UUID id,
            @RequestBody ManagerDtos.NoteRiskRequest req,
            @AuthenticationPrincipal User caller) {
        return riskCenterService.addNote(id, req, caller);
    }

    @PostMapping("/risk-center/{id}/resolve")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail riskResolve(
            @PathVariable UUID id,
            @RequestBody ManagerDtos.ResolveRiskRequest req,
            @AuthenticationPrincipal User caller) {
        return riskCenterService.resolve(id, req, caller);
    }

    @PostMapping("/risk-center/{id}/reopen")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ErmEscalationDtos.ExceptionDetail riskReopen(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return riskCenterService.reopen(id, caller);
    }
}
