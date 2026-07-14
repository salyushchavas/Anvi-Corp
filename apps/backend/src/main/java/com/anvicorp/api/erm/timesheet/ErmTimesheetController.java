package com.anvicorp.api.erm.timesheet;

import com.anvicorp.api.dto.supervised.RejectTimesheetRequest;
import com.anvicorp.api.dto.supervised.TimesheetBatchRequest;
import com.anvicorp.api.dto.supervised.TimesheetResponse;
import com.anvicorp.api.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Phase B2 — ERM verify endpoints. Read-side (the weekly rollup) is
 * served by {@code TimesheetWeekController} with a {@code scope=erm}
 * param so both ERM + Manager surfaces share one data path.
 */
@RestController
@RequestMapping("/api/v1/erm/timesheets")
@RequiredArgsConstructor
public class ErmTimesheetController {

    private final ErmTimesheetVerifyService verifyService;

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public TimesheetResponse verify(@PathVariable UUID id,
                                     @AuthenticationPrincipal User caller) {
        return verifyService.verify(id, caller);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public TimesheetResponse reject(@PathVariable UUID id,
                                     @Valid @RequestBody RejectTimesheetRequest req,
                                     @AuthenticationPrincipal User caller) {
        return verifyService.reject(id, req, caller);
    }

    @PostMapping("/verify-batch")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<UUID, String> verifyBatch(
            @Valid @RequestBody TimesheetBatchRequest req,
            @AuthenticationPrincipal User caller) {
        return verifyService.verifyBatch(req.ids(), caller);
    }
}
