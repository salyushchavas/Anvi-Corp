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
 * Manager-side approval endpoints for the two-stage weekly-report flow.
 * The Manager approves reports that the ERM has already verified —
 * mirrors the timesheet approval ownership (Manager stage sits at the
 * end of the review chain).
 *
 * <p>Approve on a specific report id stays on
 * {@link WeeklyReportController#approve} ({@code hasAnyRole('MANAGER',
 * 'SUPER_ADMIN')}); this controller adds the queue endpoint the Manager
 * dashboard uses to see all VERIFIED reports awaiting action, plus a
 * stable per-role return endpoint under {@code /api/v1/manager/…}.</p>
 */
@RestController
@RequestMapping("/api/v1/manager/weekly-reports")
@RequiredArgsConstructor
public class ManagerWeeklyReportController {

    private final WeeklyReportService service;

    /**
     * The Manager approve queue — all {@code VERIFIED} weekly reports,
     * FIFO by submitted time. Populated as ERMs verify SUBMITTED rows.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public List<WeeklyReportResponse> listPending(@AuthenticationPrincipal User caller) {
        return service.listVerifiedForManager(caller);
    }

    /**
     * Manager returns a VERIFIED report for correction. Same operation
     * as {@link WeeklyReportController#returnForCorrection}; kept here so
     * the queue → return action on the Manager dashboard has a stable
     * POST target under {@code /api/v1/manager/…}.
     */
    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public WeeklyReportResponse returnForCorrection(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewWeeklyReportRequest req,
            @AuthenticationPrincipal User caller) {
        return service.returnFromManager(id, req, caller);
    }
}
