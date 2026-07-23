package com.anvicorp.api.controller;

import com.anvicorp.api.dto.report.ReviewWeeklyReportRequest;
import com.anvicorp.api.dto.report.WeeklyReportResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.WeeklyReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Evaluator-side approval endpoints for the two-stage weekly-report flow.
 * Mirrors the ERM/Manager split on the timesheet flow — the Evaluator
 * approves reports that the ERM has already verified.
 *
 * <p>Approve / return on a specific report ID stays on
 * {@link WeeklyReportController#approve} / {@code /return} (both gated to
 * {@code EVALUATOR} + {@code SUPER_ADMIN}). This controller only adds the
 * queue endpoint the Evaluator's dashboard uses to see all VERIFIED
 * reports awaiting action.</p>
 */
@RestController
@RequestMapping("/api/v1/evaluator/weekly-reports")
@RequiredArgsConstructor
public class EvaluatorWeeklyReportController {

    private final WeeklyReportService service;

    /**
     * The Evaluator approve queue — all {@code VERIFIED} weekly reports,
     * FIFO by submitted time. Populated as ERMs verify SUBMITTED rows.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public List<WeeklyReportResponse> listPending(@AuthenticationPrincipal User caller) {
        return service.listVerifiedForEvaluator(caller);
    }

    /**
     * Evaluator returns a VERIFIED report for correction. The single-ID
     * approve endpoint is still exposed on {@link WeeklyReportController}
     * (kept there for URL back-compat with the intern-facing detail
     * routes); this endpoint only exists so the queue → return button on
     * the Evaluator dashboard has a stable POST target under
     * {@code /api/v1/evaluator/…}.
     */
    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public WeeklyReportResponse returnForCorrection(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewWeeklyReportRequest req,
            @AuthenticationPrincipal User caller) {
        return service.returnFromEvaluator(id, req, caller);
    }
}
