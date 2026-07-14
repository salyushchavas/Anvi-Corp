package com.anvicorp.api.dto.offer;

import com.anvicorp.api.enums.CompensationFrequency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateOfferRequest {

    @DecimalMin("0.01")
    private BigDecimal compensationAmount;

    private CompensationFrequency compensationFrequency;

    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    private String compensationCurrency;

    private LocalDate startDate;

    private LocalDate expectedEndDate;

    @Min(1)
    @Max(30)
    private Integer daysToRespond;

    private String additionalTerms;

    private String letterContent;
}
