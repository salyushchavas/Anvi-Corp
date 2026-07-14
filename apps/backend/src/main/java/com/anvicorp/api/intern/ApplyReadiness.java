package com.anvicorp.api.intern;

import java.util.List;

/**
 * Derived snapshot of the intern's apply-eligibility — drives both the
 * dashboard completion card and the server-side gate on
 * {@link com.anvicorp.api.service.ApplicationService#apply}.
 *
 * <p>Not a lifecycle state. Computed on demand from User + Candidate +
 * resume count. The 6 required fields are: phone, school, degree, graduation
 * year, skillset, resume. {@code missing} lists their stable keys
 * ("phone", "school", "degree", "graduationYear", "skillset", "resume") so the
 * frontend can render a checklist + the 409 PROFILE_INCOMPLETE error envelope
 * can carry the same payload back to the apply flow.
 */
public record ApplyReadiness(
        boolean complete,
        int percent,
        List<String> missing
) {}
