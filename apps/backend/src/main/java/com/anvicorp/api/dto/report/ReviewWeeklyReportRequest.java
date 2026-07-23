package com.anvicorp.api.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Reviewer → return-with-notes, approve, or verify.
 *
 * <ul>
 *   <li>{@code reviewNotes} — required on return (the intern needs to know
 *       what to fix), optional on approve. Service enforces the
 *       required-on-return rule.</li>
 *   <li>{@code ermNotes} — optional, ERM's verification note ("looks good,
 *       forwarding to Evaluator"). Only meaningful on the verify path;
 *       ignored elsewhere.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewWeeklyReportRequest {
    private String reviewNotes;
    private String ermNotes;
}
