package com.anvicorp.api.trainer.panel;

import java.util.List;

/**
 * Trainer Phase 1 — right-side panel payload. 5 quick actions from doc
 * §4 + 2 alert rows + headline counts the frontend mirrors against the
 * dashboard context (avoids duplicate fetches).
 */
public record TrainerRightPanelResponse(
        List<QuickAction> quickActions,
        List<Alert> alerts,
        long unreadNotifications,
        long todayMeetingsCount
) {

    public record QuickAction(
            String key,
            String label,
            String href,
            long badge,
            /** Phase 0/1 the actions deep-link to placeholder pages — UI
             *  renders disabled-with-tooltip when this flag is true. */
            boolean comingSoon
    ) {}

    public record Alert(
            String key,
            String label,
            long count,
            String severity   // INFO | WARN | URGENT
    ) {}
}
