package com.anvicorp.api.controller;

import com.anvicorp.api.dto.rm.ReportingManagerDashboardResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.ReportingManagerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reporting-manager")
@RequiredArgsConstructor
public class ReportingManagerController {

    private final ReportingManagerDashboardService dashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public ReportingManagerDashboardResponse dashboard(
            @AuthenticationPrincipal User caller) {
        return dashboardService.build(caller);
    }
}
