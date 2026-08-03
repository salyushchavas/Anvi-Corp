package com.anvicorp.api.controller;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.dto.supervised.InternTimesheetMonthResponse;
import com.anvicorp.api.dto.supervised.RejectTimesheetRequest;
import com.anvicorp.api.dto.supervised.ReturnTimesheetRequest;
import com.anvicorp.api.dto.supervised.TimesheetMonthRollupResponse;
import com.anvicorp.api.dto.supervised.TimesheetResponse;
import com.anvicorp.api.dto.supervised.TimesheetWeekResponse;
import com.anvicorp.api.dto.supervised.UpdateTimesheetDayRequest;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.service.timesheet.TimesheetRollupService;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.TimesheetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Day-by-day timesheet endpoints — additive surface over the existing
 * supervised flow. The legacy /api/v1/supervised/timesheets/* endpoints
 * stay live for older callers; pages on the new RM workflow call here.
 */
@RestController
@RequestMapping("/api/v1/timesheets")
@RequiredArgsConstructor
public class TimesheetWeekController {

    private final TimesheetService timesheetService;
    private final TimesheetRollupService rollupService;

    @GetMapping("/week")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public TimesheetWeekResponse getWeek(
            @RequestParam("weekStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate weekStart,
            @AuthenticationPrincipal User caller) {
        return timesheetService.getOrCreateWeek(weekStart, caller);
    }

    /**
     * Phase B1 — intern month roster. Returns the Mon–Fri work-weeks
     * touching the requested year+month, each with the intern's
     * existing timesheet row (if any). Powers the daily-entry grid on
     * the intern timesheets page.
     */
    @GetMapping("/me/month")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public InternTimesheetMonthResponse myMonth(
            @RequestParam("y") int year,
            @RequestParam("m") int month,
            @AuthenticationPrincipal User caller) {
        if (year < 1900 || year > 2999) throw new BadRequestException("y out of range");
        if (month < 1 || month > 12) throw new BadRequestException("m must be 1-12");
        return timesheetService.getMyMonth(YearMonth.of(year, month), caller);
    }

    /**
     * Phase B1 — upsert one day cell by (weekStart, dayOfWeek). The
     * service get-or-creates the parent timesheet row on demand, so
     * the intern UI doesn't have to chain two requests on the first
     * edit of each week.
     */
    @org.springframework.web.bind.annotation.PutMapping("/me/day")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public TimesheetWeekResponse saveMyDay(
            @RequestParam("weekStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate weekStart,
            @RequestParam("day") DayOfWeek day,
            @Valid @RequestBody UpdateTimesheetDayRequest req,
            @AuthenticationPrincipal User caller) {
        return timesheetService.saveDayForWeek(
                weekStart, day, req.hours(), req.notes(), caller);
    }

    @PatchMapping("/{id}/days/{day}")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public TimesheetWeekResponse patchDay(
            @PathVariable UUID id,
            @PathVariable("day") DayOfWeek day,
            @Valid @RequestBody UpdateTimesheetDayRequest req,
            @AuthenticationPrincipal User caller) {
        return timesheetService.patchDay(id, day, req.hours(), req.notes(), caller);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public TimesheetResponse submit(@PathVariable UUID id,
                                    @AuthenticationPrincipal User caller) {
        return timesheetService.submit(id, caller);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public TimesheetWeekResponse get(@PathVariable UUID id,
                                     @AuthenticationPrincipal User caller) {
        return timesheetService.getWeek(id, caller);
    }

    @GetMapping("/pending-approval")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public List<TimesheetWeekResponse> pendingApproval(
            @AuthenticationPrincipal User caller) {
        return timesheetService.listPendingApproval(caller);
    }

    /**
     * Phase B2 — shared weekly rollup for the ERM verify + Manager
     * approve surfaces. Same payload shape both ways; only the intern
     * roster differs (ERM = all active-this-month, MANAGER = managed by
     * caller). Role gate scoped per requested {@code scope}.
     */
    @GetMapping("/rollup")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'SUPER_ADMIN')")
    public TimesheetMonthRollupResponse rollup(
            @RequestParam(value = "y", required = false) Integer year,
            @RequestParam(value = "m", required = false) Integer month,
            @RequestParam(value = "month", required = false) String monthParam,
            @RequestParam("scope") String scope,
            @AuthenticationPrincipal User caller) {
        // ?month=YYYY-MM is an alias for the legacy y+m pair. When y/m
        // are supplied they win (backward compat); otherwise resolve
        // from ?month via MonthRange.parse (falls back to current
        // month on null/blank/unparseable, matching the existing
        // implicit contract).
        if (year == null || month == null) {
            MonthRange r = MonthRange.parse(monthParam);
            if (year == null) year = r.month().getYear();
            if (month == null) month = r.month().getMonthValue();
        }
        if (year < 1900 || year > 2999) throw new BadRequestException("y out of range");
        if (month < 1 || month > 12) throw new BadRequestException("m must be 1-12");
        TimesheetRollupService.Scope s;
        try {
            s = TimesheetRollupService.Scope.valueOf(scope.toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("scope must be 'erm' or 'manager'");
        }
        return rollupService.getRollup(s, java.time.YearMonth.of(year, month), caller);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public TimesheetResponse approve(@PathVariable UUID id,
                                     @AuthenticationPrincipal User caller) {
        return timesheetService.approve(id, caller);
    }

    /**
     * RM-facing alias for the existing reject endpoint. Reuses
     * {@link TimesheetService#reject} so the underlying transition stays in
     * one place; the new "return" terminology is surfaced for UI clarity.
     */
    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public TimesheetResponse returnForCorrection(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnTimesheetRequest req,
            @AuthenticationPrincipal User caller) {
        RejectTimesheetRequest mapped = new RejectTimesheetRequest();
        mapped.setReason(req.reason());
        return timesheetService.reject(id, mapped, caller);
    }
}
