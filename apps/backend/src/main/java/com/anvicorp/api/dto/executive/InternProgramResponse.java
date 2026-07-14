package com.anvicorp.api.dto.executive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Intern-program rollup. Active / completed / terminated / blocked counts
 * + completion rate + at-risk count (active interns currently behind on
 * their weekly cycle — see WeeklyCycleHealth for the granular numbers).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InternProgramResponse {
    private long activeInterns;
    private long completedInterns;
    private long terminatedInterns;
    private long blockedInterns;
    /** completed / (completed + terminated). Nullable when both are zero. */
    private Double completionRate;
    /** Active interns missing this week's report OR with a RETURNED-but-not-resubmitted report. */
    private long atRiskCount;
    /** Total FINALIZED periodic evaluations across the program. */
    private long evaluationsFinalizedCount;
    /** Average overall rating across FINALIZED evaluations (1-5 scale). Null when none. */
    private Double averageEvaluationRating;
    /** DRAFT evaluations across all supervisors — the program's "in flight" surface. */
    private long evaluationsInDraftCount;
}
