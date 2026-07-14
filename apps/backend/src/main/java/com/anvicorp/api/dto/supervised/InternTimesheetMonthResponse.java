package com.anvicorp.api.dto.supervised;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * Intern monthly timesheet roster. One entry per Mon–Fri work-week
 * touching the requested month. Each entry exposes the days that
 * actually fall in the month (edge weeks at month boundaries are
 * partial) + the existing {@link TimesheetWeekResponse} if a row has
 * already been created for that week, or {@code null} when the intern
 * hasn't touched the week yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InternTimesheetMonthResponse(
        String monthYear,
        BigDecimal monthTotalHours,
        int submittedWeeks,
        int totalWeeks,
        List<WeekEntry> weeks
) {
    public record WeekEntry(
            LocalDate weekStart,
            int weekNumber,
            List<DayOfWeek> daysInMonth,
            /** Null until the intern starts entering hours for this week. */
            TimesheetWeekResponse timesheet
    ) {}
}
