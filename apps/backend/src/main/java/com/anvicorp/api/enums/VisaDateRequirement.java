package com.anvicorp.api.enums;

import java.util.Map;

/**
 * Per-visa-track requirement for the work-auth start / end date inputs on
 * the registration intake. Used by the register form to decide which of
 * {@code validityStartDate} / {@code validityDate} (end) to show + validate.
 *
 * <p>The mapping lives in {@link #REQUIREMENT_BY_TRACK} — the SINGLE
 * editable place. The frontend mirrors this map verbatim in
 * {@code frontend/lib/visa-date-requirement.ts}; keep both in sync.</p>
 *
 * <p>Scope is the candidate-self-attestation {@link WorkAuthTrack} (4
 * values: CPT / OPT / STEM_OPT / OTHER). Post-offer compliance uses the
 * wider {@code WorkAuthorizationRecord.workAuthType} which has its own
 * date columns ({@code authorized_from}, {@code authorized_until},
 * {@code ead_expiration}, {@code i20_expiration}) — that flow is
 * unchanged by this feature.</p>
 */
public enum VisaDateRequirement {
    /** Neither start nor end date shown / required. */
    NONE,
    /** Only the end date (expiration) shown / required. */
    END_ONLY,
    /** Both start and end dates shown / required. */
    BOTH;

    /**
     * Single source of truth for which date inputs each visa track needs.
     * To change a track's requirement, edit this map only.
     */
    public static final Map<WorkAuthTrack, VisaDateRequirement> REQUIREMENT_BY_TRACK =
            Map.of(
                    WorkAuthTrack.CITIZEN, NONE,
                    WorkAuthTrack.CPT, END_ONLY,
                    WorkAuthTrack.OPT, END_ONLY,
                    WorkAuthTrack.STEM_OPT, END_ONLY,
                    WorkAuthTrack.OTHER, BOTH);

    public static VisaDateRequirement forTrack(WorkAuthTrack track) {
        if (track == null) return NONE;
        return REQUIREMENT_BY_TRACK.getOrDefault(track, BOTH);
    }
}
