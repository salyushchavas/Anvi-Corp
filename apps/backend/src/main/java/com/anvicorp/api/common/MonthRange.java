package com.anvicorp.api.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Shared month-window helper for role-dashboard month selectors.
 *
 * <p>The Evaluator dashboard was the first surface to gain a month
 * selector (Trainer / Manager / ERM follow the same pattern). Every
 * month-scopable widget on those dashboards resolves its time window
 * from this record so the boundary semantics are identical across
 * roles: the range is half-open — {@code startInclusive} is the first
 * instant of {@code month}, {@code endExclusive} is the first instant
 * of the following month. That matches the {@code
 * published_at >= start AND published_at < end} idiom the existing
 * queries already use.</p>
 *
 * <p>Instants are computed in UTC so the boundary is deterministic
 * regardless of server zone; the timestamps in the DB are all stored
 * without offset by the JPA converters, so UTC alignment gives the
 * exact same set of rows any regional deploy would.</p>
 */
public record MonthRange(YearMonth month, Instant startInclusive, Instant endExclusive) {

    /**
     * Parse a {@code YYYY-MM} query-param value. On {@code null},
     * blank, or an unparseable input, defaults to {@link YearMonth#now}
     * — the "current month" default that every controller uses when
     * the caller doesn't send a month. Callers should NEVER 400 on a
     * bad month; they degrade to current-month silently so the UI
     * always renders something.
     */
    public static MonthRange parse(String value) {
        YearMonth month;
        try {
            month = (value == null || value.isBlank())
                    ? YearMonth.now()
                    : YearMonth.parse(value.trim());
        } catch (Exception e) {
            month = YearMonth.now();
        }
        Instant start = month.atDay(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = month.plusMonths(1).atDay(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        return new MonthRange(month, start, end);
    }

    /**
     * Convenience for the {@code TIMESTAMPTZ} cast idiom used by the
     * existing dashboard queries — returns the boundary as a
     * {@code YYYY-MM-DD} string suitable for {@code ?::timestamp}
     * binding. Kept alongside {@link #startInclusive} so a query that
     * uses either boundary style reads consistent input.
     */
    public String startDateString() {
        return month.atDay(1).toString();
    }

    /** See {@link #startDateString()}. */
    public String endDateString() {
        return month.plusMonths(1).atDay(1).toString();
    }

    /** {@code YYYY-MM}. */
    public String label() {
        return month.toString();
    }

    /** True when this range's month equals {@code YearMonth.now()}. */
    public boolean isCurrent() {
        return month.equals(YearMonth.now());
    }

    /**
     * Convenience — the first day of the month as {@link LocalDate}.
     * Some existing queries bind {@code monthStart.toString()} for
     * {@code ?::timestamp} — this returns that same {@link LocalDate}
     * so a call-site can convert without recomputing.
     */
    public LocalDate monthStart() {
        return month.atDay(1);
    }

    /** First day of the FOLLOWING month (exclusive). */
    public LocalDate monthEnd() {
        return month.plusMonths(1).atDay(1);
    }
}
