package com.anvicorp.api.dto.supervised;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.TimesheetStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Day-by-day timesheet view used by the intern entry page and the RM
 * approval queue. The legacy {@link TimesheetResponse} (single weekly total)
 * stays untouched for the older callers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimesheetWeekResponse(
        UUID id,
        UUID internUserId,
        String internName,
        LocalDate weekStart,
        TimesheetStatus status,
        BigDecimal totalHours,
        List<TimesheetDayResponse> days,
        String reviewNote,
        String approvedByName,
        Instant approvedAt,
        Instant submittedAt,
        Instant createdAt
) {}
