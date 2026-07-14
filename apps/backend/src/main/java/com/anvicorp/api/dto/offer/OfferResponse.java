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
public class OfferResponse {
    private UUID id;
    private UUID applicationId;
    private String candidateName;
    private String candidateEmail;
    private UUID candidateId;
    private String jobPostingTitle;
    private UUID jobPostingId;
    private String entityName;
    private UUID entityId;
    private BigDecimal compensationAmount;
    private CompensationFrequency compensationFrequency;
    private String compensationCurrency;
    private LocalDate startDate;
    private LocalDate expectedEndDate;
    private Instant expiresAt;
    private OfferStatus status;
    private String additionalTerms;
    private String letterContent;
    private String declineReason;
    private Instant sentAt;
    private Instant respondedAt;
    private Instant revokedAt;
    private Instant createdAt;
    private String createdByName;
    private Instant updatedAt;
    private boolean isExpired;
}
