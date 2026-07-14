package com.anvicorp.api.manager.inactive;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 4B-1 — Manager's read-only Inactive Interns endpoint. Org-wide
 * for any MANAGER (and SUPER_ADMIN) — matches the rest of the Manager
 * surfaces. Period filter optional — default is "all time" since exits
 * are infrequent and the operator usually wants the full cohort.
 */
@RestController
@RequestMapping("/api/v1/manager/inactive-interns")
@RequiredArgsConstructor
public class ManagerInactiveInternsController {

    private final ManagerInactiveInternsService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerInactiveInternsDtos.InactiveInternsListResponse list(
            @RequestParam(required = false, name = "y") Integer year,
            @RequestParam(required = false, name = "m") Integer month,
            @AuthenticationPrincipal User caller) {
        if (year != null && (year < 1900 || year > 2999)) {
            throw new BadRequestException("y (year) out of range");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new BadRequestException("m (month) must be 1-12");
        }
        if ((year == null) ^ (month == null)) {
            throw new BadRequestException("y and m must be provided together");
        }
        return service.list(caller, year, month);
    }
}
