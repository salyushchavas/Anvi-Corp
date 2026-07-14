package com.anvicorp.api.controller;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.intern.InternDashboardResponse;
import com.anvicorp.api.intern.InternDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Intern-surface controller. The {@code GET /dashboard} endpoint is the
 * canonical payload driving the mode engine, stepper, sidebar visibility,
 * and Home-page next-action card — see
 * {@link com.anvicorp.api.intern.InternDashboardService}. All other
 * intern endpoints remain 501 stubs until the phase that owns them ships.
 */
@RestController
@RequestMapping("/api/v1/intern")
@RequiredArgsConstructor
@Slf4j
public class InternController {

    private static final String NOT_YET = "Intern surface is being rebuilt in Phase 1+.";

    private final InternDashboardService dashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('INTERN')")
    public InternDashboardResponse dashboard(@AuthenticationPrincipal User caller) {
        return dashboardService.getDashboard(caller);
    }

    @GetMapping("/profile")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> getProfile() {
        throw notImplemented();
    }

    @PatchMapping("/profile")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> updateProfile(@RequestBody Map<String, Object> body) {
        throw notImplemented();
    }

    @GetMapping("/projects/mine")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> myProjects() {
        throw notImplemented();
    }

    @GetMapping("/timesheets/mine")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> myTimesheets() {
        throw notImplemented();
    }

    @PostMapping("/timesheets")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> createTimesheet(@RequestBody Map<String, Object> body) {
        throw notImplemented();
    }

    @GetMapping("/evaluations/mine")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> myEvaluations() {
        throw notImplemented();
    }

    @GetMapping("/documents/mine")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> myDocuments() {
        throw notImplemented();
    }

    @GetMapping("/messages")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> messages() {
        throw notImplemented();
    }

    private ResponseStatusException notImplemented() {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, NOT_YET);
    }
}
