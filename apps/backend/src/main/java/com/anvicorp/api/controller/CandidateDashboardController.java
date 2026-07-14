package com.anvicorp.api.controller;

import com.anvicorp.api.dto.candidate.CandidateDashboardResponse;
import com.anvicorp.api.dto.candidate.DashboardStatusDTO;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.CandidateDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate read endpoint for the candidate dashboard. One round-trip
 * powers the next-step card, application stepper, upcoming events, and the
 * recent-activity feed.
 */
@RestController
@RequestMapping("/api/v1/candidate")
@RequiredArgsConstructor
public class CandidateDashboardController {

    private final CandidateDashboardService candidateDashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('INTERN')")
    public CandidateDashboardResponse dashboard(@AuthenticationPrincipal User caller) {
        return candidateDashboardService.build(caller);
    }

    /**
     * Change 2 — rich status payload for the "Your Journey" panel: overall
     * stage, the same next-step card, the full ordered timeline (with skipped
     * steps marked, not omitted), and the last-10 recent updates feed. The
     * frontend polls this every 30s while the dashboard is visible.
     */
    @GetMapping("/dashboard-status")
    @PreAuthorize("hasRole('INTERN')")
    public DashboardStatusDTO dashboardStatus(@AuthenticationPrincipal User caller) {
        return candidateDashboardService.getDashboardStatus(caller);
    }
}
