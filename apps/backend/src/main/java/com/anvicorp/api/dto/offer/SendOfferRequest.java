package com.anvicorp.api.dto.offer;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/offers/send} — the canonical
 * "create offer + dispatch via IDMS signing" flow. Renamed from the
 * legacy {@code SendDocusignOfferRequest} (DocuSign was removed Phase
 * 8.6.2); field set is identical so existing wire compatibility is
 * preserved.
 */
@Getter
@Setter
public class SendOfferRequest {

    @NotNull(message = "applicationId is required")
    private UUID applicationId;

    @NotNull(message = "tentativeStartDate is required")
    @Future(message = "tentativeStartDate must be in the future")
    private LocalDate tentativeStartDate;

    @NotBlank(message = "roleTitle is required")
    @Size(max = 200)
    private String roleTitle;

    @Size(max = 500)
    private String compensationSummary;

    @Size(max = 200)
    private String worksite;

    @Min(1)
    @Max(80)
    private Integer expectedHoursPerWeek;

    /** Offer expires if not signed within this many days. 1-60; default 7. */
    @Min(1)
    @Max(60)
    private Integer expiryDays;
}
