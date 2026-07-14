package com.anvicorp.api.erm.dashboard;

/**
 * Phase 1 — ERM dashboard scope toggle.
 *
 * <ul>
 *   <li>{@link #MINE} — filter to {@code intern_lifecycles.erm_id = caller}
 *       plus unassigned work waiting to be picked up.</li>
 *   <li>{@link #ALL} — system-wide pipeline; reserved for ERM + SUPER_ADMIN.</li>
 * </ul>
 */
public enum ErmScope {
    MINE,
    ALL;

    public static ErmScope parse(String raw) {
        if (raw == null || raw.isBlank()) return MINE;
        return switch (raw.trim().toLowerCase()) {
            case "all" -> ALL;
            case "mine" -> MINE;
            default -> throw new IllegalArgumentException(
                    "scope must be 'mine' or 'all' (got: " + raw + ")");
        };
    }

    public String wireValue() {
        return name().toLowerCase();
    }
}
