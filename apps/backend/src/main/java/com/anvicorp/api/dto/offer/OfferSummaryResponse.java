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
public class OfferSummaryResponse {
    private UUID id;
    private String candidateName;
    private String jobPostingTitle;
    private String entityName;
    private BigDecimal compensationAmount;
    private CompensationFrequency compensationFrequency;
    private LocalDate startDate;
    private Instant expiresAt;
    private OfferStatus status;
    private Instant createdAt;
}
