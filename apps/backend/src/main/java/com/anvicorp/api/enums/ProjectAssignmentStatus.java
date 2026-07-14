package com.anvicorp.api.enums;

/**
 * Per-(project, intern) assignment lifecycle. Distinct from
 * {@link ProjectStatus}, which lives on the legacy single-assignment
 * {@code Project} entity.
 *
 * <p>The assignment-module commit only ever sets {@link #ASSIGNED}.
 * The downstream values (IN_PROGRESS through COMPLETED + RETURNED) are
 * pre-declared so the future submission / evaluation / viva modules can
 * flip statuses without needing an enum-widening migration that would
 * trip Hibernate's stale-CHECK trap.</p>
 */
public enum ProjectAssignmentStatus {
    ASSIGNED,
    IN_PROGRESS,
    SUBMITTED,
    RETURNED,
    TECH_APPROVED,
    PENDING_VIVA,
    COMPLETED
}
