package com.anvicorp.api.erm.dashboard;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 — ERM right-side panel quick-actions endpoint. Polled at 60s
 * by the frontend; counts must be live so this endpoint is cache-free.
 */
@RestController
@RequestMapping("/api/v1/erm")
@RequiredArgsConstructor
public class ErmRightPanelController {

    private final ErmRightPanelService ermRightPanelService;

    @GetMapping("/right-panel")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmRightPanelResponse rightPanel(@AuthenticationPrincipal User caller) {
        return ermRightPanelService.build(caller);
    }
}
