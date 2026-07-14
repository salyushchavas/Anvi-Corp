package com.anvicorp.api.trainer.panel;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Trainer Phase 1 — right-side panel HTTP surface. */
@RestController
@RequestMapping("/api/v1/trainer/right-panel")
@RequiredArgsConstructor
public class TrainerRightPanelController {

    private final TrainerRightPanelService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public TrainerRightPanelResponse get(@AuthenticationPrincipal User caller) {
        return service.build(caller);
    }
}
