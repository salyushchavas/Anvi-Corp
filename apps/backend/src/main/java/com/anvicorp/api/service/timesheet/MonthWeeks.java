package com.anvicorp.api.service.timesheet;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the "Mon–Fri work-weeks within a month"
 * model used by the intern timesheet entry grid, the trainer roster
 * timesheet column, and the ERM rollup.
 *
 * <p><b>Bucketing rule:</b> a work-week belongs WHOLLY to the month
 * containing its Monday. Weeks are never split across two months. So a
 * Mon–Fri week of 2026-06-29 .. 2026-07-03 is a JUNE week — all five
 * weekdays appear in June's list and none of them appear in July.</p>
 *
 * <p>This is the same rule applied by the
 * {@code TIMESHEET_DUE} scheduler (write-side: {@code week_start}
 * Monday determines the period), so read-side and write-side agree by
 * construction and existing rows need no migration.</p>
 *
 * <p>Pure functions; no Spring stereotype.</p>
 */
public final class MonthWeeks {

    /** Weekdays returned by every {@link WorkWeek#daysInMonth()} — kept
     *  as a constant since under the Monday's-month rule every week is
     *  always a full Mon–Fri (no more partial weeks). */
    private static final List<DayOfWeek> FULL_WEEK = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    private MonthWeeks() {}

    /** A single Mon–Fri work-week's view of a particular month. */
    public record WorkWeek(
            /** Monday this week begins on (always in the requested month). */
            LocalDate weekStart,
            /** Position within the month, 1-based — primarily for labels. */
            int weekNumber,
            /** Weekdays Mon–Fri included in this week (always full Mon..Fri). */
            List<DayOfWeek> daysInMonth
    ) {}

    /**
     * Produce the ordered list of Mon–Fri work-weeks belonging to the
     * given year+month. A week belongs to the month containing its
     * Monday — boundary weeks are NOT split.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>June 2026 (Mon Jun 1 .. Tue Jun 30) → 5 weeks, Mondays
     *       Jun 1, 8, 15, 22, 29 (the Jun 29 week's Fri Jul 3 still
     *       belongs to June).</li>
     *   <li>July 2026 (Wed Jul 1 .. Fri Jul 31) → 4 weeks, Mondays
     *       Jul 6, 13, 20, 27 (the Jun 29 week is a JUNE week, so
     *       July's first week starts Jul 6).</li>
     * </ul>
     */
    public static List<WorkWeek> workWeeksOf(YearMonth ym) {
        if (ym == null) throw new IllegalArgumentException("YearMonth is required");
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        // First Monday that is in this month — could be day-1 itself or
        // up to 6 days later. The previous Monday (if any) belongs to the
        // PRIOR month under the new bucketing rule.
        int monStartOffset = DayOfWeek.MONDAY.getValue() - monthStart.getDayOfWeek().getValue();
        if (monStartOffset < 0) monStartOffset += 7;
        LocalDate weekStart = monthStart.plusDays(monStartOffset);

        List<WorkWeek> out = new ArrayList<>();
        int weekNumber = 0;
        while (!weekStart.isAfter(monthEnd)) {
            weekNumber++;
            out.add(new WorkWeek(weekStart, weekNumber, FULL_WEEK));
            weekStart = weekStart.plusDays(7);
        }
        return out;
    }

    /** Convenience — Set form for quick "is this day visible?" lookups. */
    public static Set<DayOfWeek> asSet(List<DayOfWeek> days) {
        return days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
    }
}
