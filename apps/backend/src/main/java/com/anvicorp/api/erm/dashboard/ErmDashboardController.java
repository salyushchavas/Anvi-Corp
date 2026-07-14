package com.anvicorp.api.erm.dashboard;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 — ERM Home dashboard HTTP surface. Read-only this phase;
 * actions land in Phases 2-7.
 */
@RestController
@RequestMapping("/api/v1/erm")
@RequiredArgsConstructor
public class ErmDashboardController {

    private final ErmDashboardService ermDashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmDashboardResponse getDashboard(
            @RequestParam(required = false, defaultValue = "mine") String scope,
            @AuthenticationPrincipal User caller) {
        return ermDashboardService.getDashboard(caller, ErmScope.parse(scope));
    }

    /**
     * Clear the cache for {@code caller} (both scopes). Returns 204.
     * Phase 2-6 events that flip relevant state can call this directly
     * (or POST to it) to keep the dashboard responsive without changing
     * the data shape.
     */
    @PostMapping("/dashboard/refresh")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Void> refresh(@AuthenticationPrincipal User caller) {
        ermDashboardService.invalidate(caller.getId(), null);
        return ResponseEntity.noContent().build();
    }
}
