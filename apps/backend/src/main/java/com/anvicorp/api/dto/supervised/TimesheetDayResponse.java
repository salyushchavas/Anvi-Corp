package com.anvicorp.api.dto.supervised;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimesheetDayResponse(
        UUID id,
        DayOfWeek dayOfWeek,
        BigDecimal hours,
        String notes
) {}
