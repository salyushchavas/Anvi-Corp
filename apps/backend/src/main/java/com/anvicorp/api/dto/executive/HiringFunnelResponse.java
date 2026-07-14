package com.anvicorp.api.dto.executive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Five-stage hiring funnel with stage-to-stage conversion rates. Same stage
 * bands the operations dashboard uses, but enriched with rate calculations
 * so leadership can spot the leakiest band at a glance.
 *
 * Rates are nullable when the denominator is zero (we don't fake a 0% rate
 * for "no candidates yet" — the frontend renders an em-dash).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HiringFunnelResponse {
    private long applied;
    private long screening;
    private long interview;
    private long offer;
    private long hired;

    /** screening / applied */
    private Double appliedToScreening;
    /** interview / screening */
    private Double screeningToInterview;
    /** offer / interview */
    private Double interviewToOffer;
    /** hired / offer */
    private Double offerToHired;
    /** hired / applied — top-of-funnel to terminal */
    private Double overall;
}
