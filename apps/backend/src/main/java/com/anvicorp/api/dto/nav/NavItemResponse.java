package com.anvicorp.api.dto.nav;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Single nav row returned to the candidate sidebar.
 *
 * <p>The frontend maps {@code key} to a lucide icon component; the backend
 * never names UI icons. Routes are absolute paths (Next.js-style).</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NavItemResponse {
    /** Stable key the frontend uses for icon lookup + seen-tracking. */
    private String key;
    private String label;
    private String route;
    /** "primary" / "history" / null for APPLICANT (single ungrouped list). */
    private String group;
    private NavBadgeResponse badge;
}
