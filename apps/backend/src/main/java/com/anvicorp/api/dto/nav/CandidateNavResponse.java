package com.anvicorp.api.dto.nav;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Top-level response for {@code GET /api/v1/candidate/nav}. The frontend
 * renders the items in order; if any item carries {@code group="history"} the
 * frontend renders a "Hiring history" subgroup header before the first such
 * row.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateNavResponse {
    /** Computed nav rows, primary group first, history group last. */
    private List<NavItemResponse> items;
    /** Convenience flag: true when the user is currently in INTERN face. */
    private boolean intern;
}
