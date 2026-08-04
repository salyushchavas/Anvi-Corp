package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.UUID;

/** Evaluator Phase 1 — read-only HTTP surface. */
@RestController
@RequestMapping("/api/v1/evaluator")
@RequiredArgsConstructor
@Slf4j
public class EvaluatorController {

    private final EvaluatorDashboardService dashboardService;
    private final EvaluatorEvalueesService evalueesService;
    private final EvaluatorRightPanelService rightPanelService;
    private final EvaluationWorkflowService workflowService;
    private final PendingEvaluationsService pendingService;
    private final PendingVivasService pendingVivasService;
    private final I983EvaluationWorkflowService i983Workflow;
    private final EvaluationHistoryService historyService;
    private final EvaluatorReportsService reportsService;
    private final EvaluatorSettingsService settingsService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorDtos.DashboardResponse dashboard(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return dashboardService.getDashboard(
                caller, com.anvicorp.api.common.MonthRange.parse(month));
    }

    @GetMapping("/active-evaluees")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorDtos.ActiveEvalueesPage activeEvaluees(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String workAuthType,
            @RequestParam(required = false) String needsAttention,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return evalueesService.list(
                caller, search, workAuthType, needsAttention,
                com.anvicorp.api.common.MonthRange.parse(month),
                page, pageSize);
    }

    @GetMapping("/evaluees/{lifecycleId}")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorDtos.EvalueeDetail evalueeDetail(
            @PathVariable UUID lifecycleId,
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return evalueesService.getDetail(
                lifecycleId, caller,
                com.anvicorp.api.common.MonthRange.parse(month));
    }

    @GetMapping("/right-panel")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorDtos.RightPanelResponse rightPanel(
            @RequestParam(required = false) UUID lifecycleId,
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return rightPanelService.get(
                lifecycleId, caller,
                com.anvicorp.api.common.MonthRange.parse(month));
    }

    // ── Phase 2 — monthly evaluation workflow ────────────────────────────

    @PostMapping("/evaluations")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail schedule(
            @RequestBody EvaluationWorkflowDtos.ScheduleRequest req,
            @AuthenticationPrincipal User caller) {
        return workflowService.schedule(req, caller);
    }

    @PostMapping("/evaluations/{id}/start")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail start(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return workflowService.start(id, caller);
    }

    @PatchMapping("/evaluations/{id}/draft")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail saveDraft(
            @PathVariable UUID id,
            @RequestBody EvaluationWorkflowDtos.SaveDraftRequest req,
            @AuthenticationPrincipal User caller) {
        return workflowService.saveDraft(id, req, caller);
    }

    @PostMapping("/evaluations/{id}/publish")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail publish(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return workflowService.publish(id, caller);
    }

    @PostMapping("/evaluations/{id}/amend")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail amend(
            @PathVariable UUID id,
            @RequestBody EvaluationWorkflowDtos.AmendRequest req,
            @AuthenticationPrincipal User caller) {
        return workflowService.amend(id, req, caller);
    }

    @GetMapping("/evaluations/{id}")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail getEvaluation(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return workflowService.getEvaluatorDetail(id, caller);
    }

    @GetMapping("/pending-evaluations")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.PendingEvaluationsResponse pendingEvaluations(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return pendingService.list(caller,
                com.anvicorp.api.common.MonthRange.parse(month));
    }

    /**
     * Evaluator pending-Q&A queue — projects whose status is PENDING_VIVA
     * (trainer approved; awaiting evaluator's Q&A session + final
     * approval). Scoped to lifecycles where {@code evaluator_id} matches
     * the caller or is null (single-evaluator-org fallback).
     *
     * <p>Wires into the existing {@code /api/v1/qa-sessions} surface —
     * the frontend uses this list to schedule sessions, record conducted
     * notes, sign off (marks + remarks), or return for revisions.</p>
     */
    @GetMapping("/pending-vivas")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public PendingVivasDtos.PendingVivasResponse pendingVivas(
            @AuthenticationPrincipal User caller) {
        return pendingVivasService.list(caller);
    }

    // ── Phase 3 — I-983 evaluation workflow ──────────────────────────────

    @GetMapping("/i983-evaluations")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.I983ListResponse i983List(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.list(caller,
                com.anvicorp.api.common.MonthRange.parse(month));
    }

    @PostMapping("/i983-evaluations")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.EvaluatorI983Detail i983Schedule(
            @RequestBody I983WorkflowDtos.ScheduleI983Request req,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.schedule(req, caller);
    }

    @PostMapping("/i983-evaluations/{id}/start")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.EvaluatorI983Detail i983Start(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.start(id, caller);
    }

    @PatchMapping("/i983-evaluations/{id}/draft")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.EvaluatorI983Detail i983SaveDraft(
            @PathVariable UUID id,
            @RequestBody I983WorkflowDtos.SaveDraftI983Request req,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.saveDraft(id, req, caller);
    }

    @PostMapping("/i983-evaluations/{id}/publish")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.EvaluatorI983Detail i983Publish(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.publish(id, caller);
    }

    @PostMapping("/i983-evaluations/{id}/amend")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.EvaluatorI983Detail i983Amend(
            @PathVariable UUID id,
            @RequestBody I983WorkflowDtos.AmendI983Request req,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.amend(id, req, caller);
    }

    @PostMapping("/i983-evaluations/{id}/mark-dso-submitted")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.EvaluatorI983Detail i983MarkDsoSubmitted(
            @PathVariable UUID id,
            @RequestBody I983WorkflowDtos.MarkDsoSubmittedRequest req,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.markDsoSubmitted(id, req, caller);
    }

    @GetMapping("/i983-evaluations/{id}")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public I983WorkflowDtos.EvaluatorI983Detail i983Get(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return i983Workflow.getEvaluatorDetail(id, caller);
    }

    // ── Phase 4 — Final evaluation scheduling ────────────────────────────

    @PostMapping("/final-evaluations")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail scheduleFinal(
            @RequestBody EvaluationWorkflowDtos.ScheduleFinalRequest req,
            @AuthenticationPrincipal User caller) {
        return workflowService.scheduleFinal(req, caller);
    }

    // ── Phase 4 — Evaluation History ─────────────────────────────────────

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorPhase4Dtos.HistoryPage history(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return historyService.list(caller, search, type, status,
                com.anvicorp.api.common.MonthRange.parse(month),
                page, pageSize);
    }

    // ── Phase 4 — Monthly Reports ────────────────────────────────────────

    @GetMapping("/reports/monthly")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorPhase4Dtos.MonthlyReport monthlyReport(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @AuthenticationPrincipal User caller) {
        return reportsService.monthly(caller, year, month);
    }

    @GetMapping("/reports/monthly.csv")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public ResponseEntity<StreamingResponseBody> monthlyReportCsv(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @AuthenticationPrincipal User caller) {
        StreamingResponseBody body = out -> {
            try {
                reportsService.streamMonthlyCsv(caller, year, month, out);
            } catch (Exception e) {
                log.warn("[EvaluatorCtl] reports CSV stream failed: {}", e.getMessage());
                throw e;
            }
        };
        int y = year != null ? year : LocalDate.now().getYear();
        int m = month != null ? month : LocalDate.now().getMonthValue();
        String fileName = String.format("evaluator-report-%04d-%02d.csv", y, m);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }

    // ── Phase 4 — Settings ───────────────────────────────────────────────

    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorPhase4Dtos.EvaluatorSettings getSettings(
            @AuthenticationPrincipal User caller) {
        return settingsService.get(caller);
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluatorPhase4Dtos.EvaluatorSettings updateSettings(
            @RequestBody EvaluatorPhase4Dtos.SettingsUpdateRequest req,
            @AuthenticationPrincipal User caller) {
        return settingsService.update(req, caller);
    }
}
