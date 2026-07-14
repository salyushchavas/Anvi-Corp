package com.anvicorp.api.dto.offer;

import com.anvicorp.api.enums.CompensationFrequency;
import com.anvicorp.api.enums.OfferStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateOfferResponse {
    private UUID id;
    private String jobPostingTitle;
    private String entityName;
    private BigDecimal compensationAmount;
    private CompensationFrequency compensationFrequency;
    private String compensationCurrency;
    private LocalDate startDate;
    private LocalDate expectedEndDate;
    private Instant expiresAt;
    private OfferStatus status;
    private String additionalTerms;
    private String letterContent;
    private Instant sentAt;
    private Instant respondedAt;
    private boolean isExpired;

    // ── Phase 3 doc-spec fields (applicant-safe; no voided_reason unless own VOIDED) ──
    private String roleTitle;
    private String compensationSummary;
    private String worksite;
    private Integer expectedHoursPerWeek;
    /** Legacy external-signing envelope id from the pre-IDMS integration.
     *  Always null on new offers; preserved on historical rows. */
    private String legacyEnvelopeId;
    private Instant signedAt;
    private Instant voidedAt;
    private String voidedReason;
    /** Legacy archived signed-PDF document id (pre-IDMS). Null on
     *  IDMS-signed rows — IDMS stores the signature image inline. */
    private UUID signedPdfDocumentId;
    /** Convenience for the Home + Offer pages. */
    private String employeeId;
    /** ERM contact who created the offer (for the "questions?" footer). */
    private String createdByName;
    private String createdByEmail;
}
