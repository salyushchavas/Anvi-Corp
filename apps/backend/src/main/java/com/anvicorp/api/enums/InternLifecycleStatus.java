package com.anvicorp.api.enums;

/**
 * Canonical applicant-to-intern lifecycle status. Mirrors the 13-state
 * journey described in the Applicant-to-Intern Lifecycle doc and is the
 * single source of truth the Phase-1 mode engine reads from to derive
 * the intern dashboard's mode.
 *
 * <p>Other status enums in the codebase ({@link ApplicationStatus},
 * {@link EngagementStatus}, …) stay for their own concerns; this enum
 * is the intern's overall position in the funnel.</p>
 *
 * <p>The values are declared in lifecycle order — never reorder; ordinal
 * is incidental but downstream queries may rely on the textual name.</p>
 */
public enum InternLifecycleStatus {
    REGISTERED,
    EMAIL_VERIFIED,
    APPLICATION_SUBMITTED,
    SHORTLISTED,
    INTERVIEW_SCHEDULED,
    INTERVIEW_COMPLETED,
    OFFER_SENT,
    OFFER_SIGNED,
    EMPLOYEE_ID_CREATED,
    ONBOARDING_ASSIGNED,
    ONBOARDING_ACCEPTED,
    ACTIVE_INTERN,
    INACTIVE_INTERN
}
