package com.anvicorp.api.dto.executive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Compliance rollup — aggregate completion percentages + the count of
 * authorizations expiring within 90 days. No names, no individual records;
 * leadership sees the program-wide pulse, not the candidate list.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceHealthResponse {
    /** Total active interns considered in the denominator. */
    private long activeInternsTotal;
    /** Active interns whose I-9 is COMPLETED. */
    private long clearedCount;
    /** clearedCount / activeInternsTotal. Nullable when activeInternsTotal == 0. */
    private Double clearedRate;

    private long i9CompletedCount;
    private long i9TotalCount;
    /** i9CompletedCount / i9TotalCount. Nullable when i9TotalCount == 0. */
    private Double i9CompletionRate;

    private long everifyAuthorizedCount;
    private long everifyTotalCount;
    /** everifyAuthorizedCount / everifyTotalCount. Nullable when everifyTotalCount == 0. */
    private Double everifyCompletionRate;

    /** Distinct candidate count whose work-auth or STEM OPT end-date is within 90 days. */
    private long authorizationsExpiringSoonCount;
}
