package com.anvicorp.api.dto.project;

import lombok.*;

/**
 * Supervisor review — used by both {@code /return} (notes required) and
 * {@code /complete} (notes optional). Same shape as the weekly-report
 * review request so the FE can reuse its form pattern.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewProjectRequest {
    private String reviewNotes;
}
