package com.anvicorp.api.dto.offer;

import com.anvicorp.api.enums.CompensationFrequency;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateOfferRequest {

    @NotNull
    private UUID applicationId;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal compensationAmount;

    @NotNull
    private CompensationFrequency compensationFrequency;

    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    @Builder.Default
    private String compensationCurrency = "USD";

    @NotNull
    private LocalDate startDate;

    private LocalDate expectedEndDate;

    @NotNull
    @Min(1)
    @Max(30)
    @Builder.Default
    private Integer daysToRespond = 7;

    private String additionalTerms;
}
