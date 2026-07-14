package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UpdateTimesheetRequest {

    @NotNull(message = "hours is required")
    @DecimalMin(value = "0.01", message = "hours must be greater than 0")
    @DecimalMax(value = "168.00", message = "hours cannot exceed 168 in a week")
    private BigDecimal hours;

    private String description;
}
