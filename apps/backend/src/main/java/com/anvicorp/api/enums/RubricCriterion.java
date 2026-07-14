package com.anvicorp.api.enums;

/**
 * Rubric criteria scored 1–5 in an {@link com.anvicorp.api.entity.Evaluation}.
 * Stored structured (one row per criterion in
 * {@code evaluation_rubric_scores}) so the executive dashboard can aggregate
 * averages per criterion across the program.
 *
 * <p>The six chosen here cover the intern workspace dimensions surfaced
 * elsewhere in the app (project quality, weekly-cycle hygiene, growth).
 * Adding criteria is additive at the enum + UI level.
 */
public enum RubricCriterion {
    TECHNICAL_SKILLS,
    CODE_QUALITY,
    COMMUNICATION,
    INITIATIVE,
    PROFESSIONALISM,
    LEARNING
}
