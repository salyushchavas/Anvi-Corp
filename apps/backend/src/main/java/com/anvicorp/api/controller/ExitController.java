package com.anvicorp.api.controller;

import com.anvicorp.api.dto.exit.ExitDtos;
import com.anvicorp.api.entity.ExitRecord;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.service.ExitService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 8 — exit lifecycle HTTP surface. ERM owns the write operations
 * (initiate, amend within 7-day window, checklist updates, link final
 * eval, retry revocation, list/get). The departing intern owns their
 * own record view + one-time feedback submission.
 */
@RestController
@RequestMapping("/api/v1/exit")
@RequiredArgsConstructor
public class ExitController {

    private final ExitService exitService;

    // ── ERM endpoints ───────────────────────────────────────────────────────

    @PostMapping("/records")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ExitDtos.ExitRecordResponse initiate(
            @RequestBody ExitDtos.CreateExitRecordRequest req,
            @AuthenticationPrincipal User actor) {
        return exitService.initiate(req, actor);
    }

    @PatchMapping("/records/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ExitDtos.ExitRecordResponse amend(
            @PathVariable UUID id,
            @RequestBody ExitDtos.PatchExitRecordRequest req,
            @AuthenticationPrincipal User actor) {
        return exitService.amend(id, req, actor);
    }

    @PostMapping("/records/{id}/checklist/{itemKey}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ExitDtos.ExitRecordResponse updateChecklist(
            @PathVariable UUID id,
            @PathVariable String itemKey,
            @RequestBody ExitDtos.ChecklistRequest req,
            @AuthenticationPrincipal User actor) {
        return exitService.updateChecklist(id, itemKey, req, actor);
    }

    @PostMapping("/records/{id}/final-evaluation")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ExitDtos.ExitRecordResponse linkFinalEvaluation(
            @PathVariable UUID id,
            @RequestBody ExitDtos.LinkFinalEvaluationRequest req,
            @AuthenticationPrincipal User actor) {
        return exitService.linkFinalEvaluation(id, req, actor);
    }

    @PostMapping("/records/{id}/retry-revocation")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ExitDtos.ExitRecordResponse retryRevocation(
            @PathVariable UUID id,
            @AuthenticationPrincipal User actor) {
        return exitService.retryRevocation(id, actor);
    }

    @GetMapping("/records")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'SUPER_ADMIN')")
    public ExitDtos.ExitRecordListPage list(
            @RequestParam(required = false) String exitType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        LocalDate fromDate = from != null && !from.isBlank() ? LocalDate.parse(from) : null;
        LocalDate toDate = to != null && !to.isBlank() ? LocalDate.parse(to) : null;
        return exitService.list(exitType, fromDate, toDate, page, pageSize);
    }

    @GetMapping("/records/{id}")
    @PreAuthorize("isAuthenticated()")
    public Object getById(@PathVariable UUID id,
                           @AuthenticationPrincipal User caller) {
        ExitDtos.ExitRecordResponse staff = exitService.getById(id, caller);
        if (isStaff(caller)) {
            return staff;
        }
        // INTERN — must be the owner. Return intern-safe view (no internal_notes).
        if (caller == null || !caller.getId().equals(staff.internId())) {
            throw new ForbiddenException("Not authorised to view this exit record");
        }
        return exitService.getInternView(id);
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ExitDtos.PendingExitItem> pending() {
        return exitService.pending();
    }

    // ── Intern endpoints ────────────────────────────────────────────────────

    @GetMapping("/my-record")
    @PreAuthorize("hasRole('INTERN')")
    public ExitDtos.ExitRecordInternView myRecord(@AuthenticationPrincipal User intern) {
        ExitRecord record = exitService.findByInternUser(intern.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No exit record for this user"));
        return exitService.toInternView(record);
    }

    @GetMapping("/my-summary")
    @PreAuthorize("hasRole('INTERN')")
    public ExitDtos.ExitSummaryResponse mySummary(@AuthenticationPrincipal User intern) {
        return exitService.getInternSummary(intern)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Caller is not an exited intern"));
    }

    @GetMapping("/feedback/mine")
    @PreAuthorize("hasRole('INTERN')")
    public ExitDtos.ExitFeedbackResponse myFeedback(@AuthenticationPrincipal User intern) {
        return exitService.getFeedbackForIntern(intern.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Feedback not submitted yet"));
    }

    @PostMapping("/feedback")
    @PreAuthorize("hasRole('INTERN')")
    public ExitDtos.ExitFeedbackResponse submitFeedback(
            @RequestBody ExitDtos.SubmitFeedbackRequest req,
            @AuthenticationPrincipal User intern) {
        return exitService.submitFeedback(req, intern);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean isStaff(User u) {
        if (u == null || u.getRoles() == null) return false;
        return u.getRoles().stream().anyMatch(r ->
                r == UserRole.ERM || r == UserRole.MANAGER || r == UserRole.SUPER_ADMIN);
    }

    @SuppressWarnings("unused")
    private static Optional<UUID> uuidOrNull(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        try { return Optional.of(UUID.fromString(s)); }
        catch (Exception e) { return Optional.empty(); }
    }
}
