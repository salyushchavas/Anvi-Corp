package com.anvicorp.api.erm.report;

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
 * ERM-side verification endpoints for the two-stage weekly-report flow.
 * Mirrors {@link com.anvicorp.api.erm.timesheet.ErmTimesheetController}:
 * queue of {@code SUBMITTED} reports + verify / return actions. Scope is
 * org-wide — any ERM (or SUPER_ADMIN) may act on any intern's submitted
 * report so a week never gets stuck behind one ERM's vacation.
 */
@RestController
@RequestMapping("/api/v1/erm/weekly-reports")
@RequiredArgsConstructor
public class ErmWeeklyReportController {

    private final WeeklyReportService service;

    /**
     * The ERM verify queue — all {@code SUBMITTED} weekly reports, sorted
     * oldest-submission-first (FIFO). Powers the ERM → Weekly Reports
     * screen; drops APPROVED / VERIFIED / RETURNED / DRAFT out of the
     * view since they aren't waiting on the ERM.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<WeeklyReportResponse> listSubmitted(@AuthenticationPrincipal User caller) {
        return service.listSubmittedForErm(caller);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public WeeklyReportResponse verify(@PathVariable UUID id,
                                        @RequestBody(required = false) ReviewWeeklyReportRequest req,
                                        @AuthenticationPrincipal User caller) {
        return service.verify(id, req, caller);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public WeeklyReportResponse returnForCorrection(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewWeeklyReportRequest req,
            @AuthenticationPrincipal User caller) {
        return service.returnFromErm(id, req, caller);
    }
}
