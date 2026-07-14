package com.anvicorp.api.controller;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.intern.InternRightPanelService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Phase 7 right-side panel endpoint. Returns the four contacts +
 * bell unread count + rule-driven compliance reminders so the frontend
 * panel renders verbatim without re-deriving state client-side.
 */
@RestController
@RequestMapping("/api/v1/intern")
@RequiredArgsConstructor
public class InternRightPanelController {

    private final InternRightPanelService rightPanelService;

    @GetMapping("/right-panel")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> rightPanel(@AuthenticationPrincipal User caller) {
        return rightPanelService.build(caller);
    }
}
