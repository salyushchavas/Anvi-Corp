package com.anvicorp.api.controller;

import com.anvicorp.api.dto.executive.ExecutiveDashboardResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.ExecutiveDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only aggregate endpoint for the Executive (leadership) dashboard.
 *
 * <h2>Roles</h2>
 * Gated to {@code EXECUTIVE} and {@code SUPER_ADMIN}. Operations,
 * HR, TECHNICAL_EVALUATOR, APPLICANT, INTERN are all 403.
 *
 * <h2>Privilege guarantee</h2>
 * This controller has NO companion write endpoints — no POST / PUT / DELETE
 * anywhere under {@code /api/v1/executive/*}. The DTO surfaces only
 * aggregate counts + rates + supervisor names (staff identities, not
 * regulated PII). The frontend renders it with zero action buttons.
 */
@RestController
@RequestMapping("/api/v1/executive")
@RequiredArgsConstructor
public class ExecutiveDashboardController {

    private final ExecutiveDashboardService executiveDashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ExecutiveDashboardResponse dashboard(@AuthenticationPrincipal User caller) {
        return executiveDashboardService.build(caller);
    }
}
