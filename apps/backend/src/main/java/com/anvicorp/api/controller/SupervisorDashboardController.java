package com.anvicorp.api.controller;

import com.anvicorp.api.dto.supervisor.SupervisorDashboardResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.SupervisorDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate read endpoint for the Technical Evaluator dashboard.
 *
 * <h2>Roles</h2>
 * Gated to {@code TECHNICAL_EVALUATOR} and {@code SUPER_ADMIN}. Operations,
 * HR, EXECUTIVE, APPLICANT, INTERN are all 403.
 *
 * <h2>Scope</h2>
 * The service restricts a TECHNICAL_EVALUATOR's view to ACTIVE engagements
 * where they are the {@code Engagement.supervisor}. SUPER_ADMIN bypasses
 * the scope and sees every active engagement.
 *
 * <h2>Privilege boundary</h2>
 * The service depends on no compliance repositories and surfaces no
 * compliance PII; no audit-log export controls are exposed here.
 */
@RestController
@RequestMapping("/api/v1/supervisor")
@RequiredArgsConstructor
public class SupervisorDashboardController {

    private final SupervisorDashboardService supervisorDashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public SupervisorDashboardResponse dashboard(@AuthenticationPrincipal User caller) {
        return supervisorDashboardService.build(caller);
    }
}
