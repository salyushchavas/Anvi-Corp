package com.anvicorp.api.erm.dashboard;

/**
 * Phase 1 — one KPI cell on the ERM Home grid.
 *
 * @param key          stable identifier the frontend uses for layout / icon
 * @param label        short title shown in the card header
 * @param count        total matching rows
 * @param urgentCount  subset of {@code count} past the urgency threshold
 * @param helperText   optional one-line context shown under the count
 * @param actionUrl    deep link the card hands off to when clicked
 */
public record KpiSnapshot(
        ErmKpiKey key,
        String label,
        long count,
        long urgentCount,
        String helperText,
        String actionUrl
) {}
