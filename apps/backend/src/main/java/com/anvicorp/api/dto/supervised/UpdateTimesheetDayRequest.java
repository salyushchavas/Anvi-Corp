package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateTimesheetDayRequest(
        @DecimalMin("0.0") @DecimalMax("24.0") BigDecimal hours,
        @Size(max = 2000) String notes
) {}
