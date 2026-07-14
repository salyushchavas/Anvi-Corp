package com.anvicorp.api.enums;

/**
 * Structured education level captured at registration / profile update.
 *
 * <p>Replaces the legacy free-text {@code Candidate.degree} column for
 * new records. Pre-migration rows that wrote into {@code Candidate.education}
 * (free text) are read as-is; the structured columns can stay null.</p>
 *
 * <p>If a future cohort surfaces a degree this set doesn't cover, prefer
 * {@link #OTHER} over expanding the enum — adding values requires
 * realigning the {@code candidates_degree_level_check} constraint via
 * {@code SchemaFixupRunner.alignEnumCheckConstraintsV2}.</p>
 */
public enum DegreeLevel {
    HIGH_SCHOOL,
    ASSOCIATE,
    DIPLOMA,
    BACHELORS,
    MASTERS,
    MBA,
    /** Canonical PhD / doctoral value. Kept under the original name
     *  because {@code I983Plan} rows in production already persist it. */
    DOCTORATE,
    OTHER
}
