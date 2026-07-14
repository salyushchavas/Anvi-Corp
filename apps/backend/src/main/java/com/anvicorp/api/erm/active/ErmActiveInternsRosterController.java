package com.anvicorp.api.erm.active;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.trainer.active.ActiveInternsDtos;
import com.anvicorp.api.trainer.active.ActiveInternsService;
import com.anvicorp.api.trainer.active.ActiveInternsService.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase C — ERM view of the shared monthly intern roster. Thin wrapper
 * over {@link ActiveInternsService#list} with {@code Scope.ALL} (no
 * SQL filter) since the ERM owns the whole intern population. The
 * {@code MonthRosterSummary.noManager} count + per-row managerId tell
 * the ERM UI which interns to flag for the AssignManager flow.
 *
 * <p>Routed under {@code /api/v1/erm/active-interns/roster} so it
 * coexists with the legacy 6-card monitor at
 * {@code /api/v1/erm/active-interns} for any older callers — the new
 * ERM UI uses this one.</p>
 */
@RestController
@RequestMapping("/api/v1/erm/active-interns/roster")
@RequiredArgsConstructor
public class ErmActiveInternsRosterController {

    private final ActiveInternsService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ActiveInternsDtos.ActiveInternListPage list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, name = "projectState") List<String> projectFilter,
            @RequestParam(required = false, name = "meetingState") List<String> meetingFilter,
            @RequestParam(required = false, name = "evaluationState") List<String> evaluationFilter,
            @RequestParam(required = false, name = "timesheetState") List<String> timesheetFilter,
            @RequestParam(required = false, name = "y") Integer year,
            @RequestParam(required = false, name = "m") Integer month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int pageSize,
            @AuthenticationPrincipal User caller) {
        if (year != null && (year < 1900 || year > 2999)) {
            throw new BadRequestException("y (year) out of range");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new BadRequestException("m (month) must be 1-12");
        }
        return service.list(caller, search,
                projectFilter, meetingFilter, evaluationFilter, timesheetFilter,
                year, month, page, pageSize, Scope.ALL);
    }
}
