package com.anvicorp.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

/**
 * Payload for the recruiter review screen's one-click Shortlist / Reject actions.
 * Both fields are optional — the action goes through whether or not the recruiter
 * filled in a rating or note.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecruiterDecisionRequest {

    /** 1-5 star rating (optional). */
    @Min(1)
    @Max(5)
    private Integer rating;

    /** Recruiter's note explaining the decision (optional). */
    private String note;

    /**
     * Phase 2 — applicant-facing feedback string set on reject (and surfaced
     * on the applicant's My Applications detail page). Optional in the wire
     * format so legacy callers still validate; the controller enforces a
     * ≥10-char minimum on reject. The internal {@code note} above stays
     * staff-only.
     */
    @jakarta.validation.constraints.Size(max = 4000)
    private String applicantVisibleFeedback;
}
