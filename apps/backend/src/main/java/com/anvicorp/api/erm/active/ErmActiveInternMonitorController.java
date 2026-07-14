package com.anvicorp.api.erm.active;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** ERM Phase 6 — Active Intern Monitor HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/active-interns")
@RequiredArgsConstructor
public class ErmActiveInternMonitorController {

    private final ErmActiveInternMonitorService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmActiveDtos.ActiveInternListPage list(
            @RequestParam(required = false, defaultValue = "all") String scope,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) UUID trainerId,
            @RequestParam(required = false) UUID evaluatorId,
            @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.list(scope, state, trainerId, evaluatorId, managerId,
                search, caller, page, pageSize);
    }

    @GetMapping("/{lifecycleId}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmActiveDtos.InternMonitorView getMonitor(
            @PathVariable UUID lifecycleId,
            @AuthenticationPrincipal User caller) {
        return service.getMonitor(lifecycleId, caller);
    }
}
