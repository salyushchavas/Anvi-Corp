package com.anvicorp.api.dto.admin;

import lombok.*;

import java.util.Map;

/**
 * At-a-glance platform stats for the admin landing page. Every count defaults
 * to 0 — an empty system returns this fully-formed shape, never nulls.
 *
 * {@code applicationsByStatus} keys are the real {@link com.anvicorp.api.enums.ApplicationStatus}
 * enum names, populated for every value (zero where there are no rows) so the
 * frontend can iterate without missing-key checks.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminOverviewResponse {

    private long totalCandidates;
    private long totalHired;
    /** Distinct candidates with at least one HIRED application. */
    private long activeInterns;
    /** Job postings in OPEN status. */
    private long openPostings;

    /** Real-enum-name -> count. All ApplicationStatus values present (zero-filled). */
    private Map<String, Long> applicationsByStatus;

    private ComplianceCounts compliance;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ComplianceCounts {
        private long i9Pending;
        private long i983Pending;
        private long everifyPending;
    }
}
