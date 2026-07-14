package com.anvicorp.api.trainer.meetings;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/** Trainer weekly-sessions tracker — read-only roll-up surface.
 *  Schedule + complete + cancel actions stay on the existing
 *  {@code /api/v1/trainer/weekly-meetings} controller. */
@RestController
@RequestMapping("/api/v1/trainer/weekly-tracker")
@RequiredArgsConstructor
public class TrainerWeeklyTrackerController {

    private final WeeklyTrackerService trackerService;

    /**
     * Per-month tracker payload.
     *
     * @param y year (optional — defaults to current UTC year)
     * @param m month 1-12 (optional — defaults to current UTC month)
     * @param internLifecycleId when set, narrows to one intern (the
     *        per-intern view embedded on the intern detail page)
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public WeeklyTrackerDtos.TrackerResponse get(
            @RequestParam(required = false) Integer y,
            @RequestParam(required = false) Integer m,
            @RequestParam(required = false) UUID internLifecycleId,
            @AuthenticationPrincipal User caller) {
        YearMonth period;
        if (y != null && m != null) {
            period = YearMonth.of(y, m);
        } else {
            period = YearMonth.now(ZoneOffset.UTC);
        }
        return trackerService.buildTracker(period, internLifecycleId, caller);
    }
}
