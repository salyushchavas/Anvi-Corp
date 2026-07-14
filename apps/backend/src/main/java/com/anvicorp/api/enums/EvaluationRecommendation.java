package com.anvicorp.api.enums;

/**
 * Headline recommendation the supervisor records when finalizing an
 * evaluation. Lives on the evaluation, not the rubric, so the executive
 * dashboard can aggregate across all interns regardless of which
 * criteria were scored.
 */
public enum EvaluationRecommendation {
    HIGHLY_RECOMMENDED,
    RECOMMENDED,
    NEEDS_IMPROVEMENT,
    NOT_RECOMMENDED
}
