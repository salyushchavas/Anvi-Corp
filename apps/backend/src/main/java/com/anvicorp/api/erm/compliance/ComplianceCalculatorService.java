package com.anvicorp.api.erm.compliance;

import com.anvicorp.api.erm.exception.ExceptionSeverity;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * ERM Phase 5 — pure business-day calculator for the Compliance Tracker.
 * Federal rules referenced here:
 * <ul>
 *   <li>I-9 Section 2: completed by the 3rd business day after the
 *       employee's first day of employment.</li>
 *   <li>E-Verify case: created by the 3rd business day after the first day
 *       of employment.</li>
 *   <li>TNC contest window: 10 federal business days from FFS issuance.</li>
 * </ul>
 *
 * <p>Holidays are intentionally deferred — Mon-Fri arithmetic only. The
 * service is stateless so it composes cleanly inside other services + the
 * scheduled alert job.</p>
 */
@Service
public class ComplianceCalculatorService {

    public static final int I9_SECTION2_BUSINESS_DAYS = 3;
    public static final int EVERIFY_BUSINESS_DAYS = 3;
    public static final int TNC_CONTEST_BUSINESS_DAYS = 10;

    /** First day + N business days, skipping Sat/Sun. */
    public LocalDate addBusinessDays(LocalDate from, int businessDays) {
        if (from == null) return null;
        if (businessDays <= 0) return from;
        LocalDate cursor = from;
        int added = 0;
        while (added < businessDays) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor)) {
                added++;
            }
        }
        return cursor;
    }

    /** Mon-Fri are business days; Sat/Sun are not. Holidays deferred. */
    public boolean isBusinessDay(LocalDate d) {
        if (d == null) return false;
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
    }

    /** Negative means overdue. Null target ⇒ null. */
    public Integer daysUntil(LocalDate target, LocalDate today) {
        if (target == null || today == null) return null;
        return (int) (target.toEpochDay() - today.toEpochDay());
    }

    /** I-9 §2 due-by from the employee's first day. */
    public LocalDate i9Section2DueBy(LocalDate firstDayOfEmployment) {
        return addBusinessDays(firstDayOfEmployment, I9_SECTION2_BUSINESS_DAYS);
    }

    /** E-Verify case-open due-by from the employee's first day. */
    public LocalDate everifyDueBy(LocalDate firstDayOfEmployment) {
        return addBusinessDays(firstDayOfEmployment, EVERIFY_BUSINESS_DAYS);
    }

    /** TNC contest deadline from FFS issuance date. */
    public LocalDate tncContestDueBy(LocalDate ffsIssuedOn) {
        return addBusinessDays(ffsIssuedOn, TNC_CONTEST_BUSINESS_DAYS);
    }

    /**
     * Severity for a daysUntil value. The doc thresholds:
     * <ul>
     *   <li>≤0 days → URGENT (due today or overdue)</li>
     *   <li>≤2 days → WARN</li>
     *   <li>≤7 days → INFO</li>
     *   <li>otherwise → null (no alert)</li>
     * </ul>
     */
    public ExceptionSeverity alertSeverity(Integer daysUntil) {
        if (daysUntil == null) return null;
        if (daysUntil <= 0) return ExceptionSeverity.URGENT;
        if (daysUntil <= 2) return ExceptionSeverity.WARN;
        if (daysUntil <= 7) return ExceptionSeverity.INFO;
        return null;
    }
}
