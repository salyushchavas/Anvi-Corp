package com.anvicorp.api.erm.dashboard;

import java.util.List;

/**
 * Phase 1 — right-side panel payload for the ERM surface. Distinct from
 * the intern panel (contacts) — ERM gets quick-action shortcuts with
 * live badge counts.
 */
public record ErmRightPanelResponse(
        List<QuickAction> quickActions,
        long unreadNotifications,
        long todayInterviewsCount
) {

    public record QuickAction(
            String key,
            String label,
            String href,
            long badge
    ) {}
}
