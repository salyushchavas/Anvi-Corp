package com.anvicorp.api.erm.dashboard;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 — ERM right-side panel quick-actions endpoint. Polled at 60s
 * by the frontend; counts must be live so this endpoint is cache-free.
 *
 * <p>Accepts an optional {@code ?month=YYYY-MM}; past months return an
 * empty panel (zero-badged actions, 0 unread, 0 today) so the "empty
 * past month" rule holds across every ERM read.</p>
 */
@RestController
@RequestMapping("/api/v1/erm")
@RequiredArgsConstructor
public class ErmRightPanelController {

    private final ErmRightPanelService ermRightPanelService;

    @GetMapping("/right-panel")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmRightPanelResponse rightPanel(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return ermRightPanelService.build(caller, MonthRange.parse(month));
    }
}
