package com.anvicorp.api.trainer.dashboard;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Trainer Phase 1 — Home dashboard HTTP surface.
 *
 * <p>Accepts an optional {@code ?month=YYYY-MM} that scopes month-bound
 * KPIs (projects, sessions, meetings, KTs) to that month. Absent /
 * unparseable defaults to the current month via {@link MonthRange#parse}
 * — so current-month callers see byte-identical output vs. before the
 * sticky-month feature.</p>
 */
@RestController
@RequestMapping("/api/v1/trainer/dashboard")
@RequiredArgsConstructor
public class TrainerDashboardController {

    private final TrainerDashboardService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public TrainerDashboardResponse get(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return service.getDashboard(caller, MonthRange.parse(month));
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ResponseEntity<Void> refresh(@AuthenticationPrincipal User caller) {
        service.invalidate(caller);
        return ResponseEntity.noContent().build();
    }
}
