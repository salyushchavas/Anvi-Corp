package com.anvicorp.api.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Supervisor → return-with-notes or approve. {@code reviewNotes} is required
 * on return (the intern needs to know what to fix), optional on approve.
 * Service enforces the required-on-return rule.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewWeeklyReportRequest {
    private String reviewNotes;
}
