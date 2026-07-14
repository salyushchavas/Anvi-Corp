package com.anvicorp.api.erm.interview;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ERM Phase 3 — interview scheduler + decision center HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/interviews")
@RequiredArgsConstructor
public class ErmInterviewController {

    private final ErmInterviewService ermInterviewService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmInterviewDtos.ErmInterviewListPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID interviewerId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "mine") String scope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return ermInterviewService.list(status, interviewerId, search,
                scope, caller, page, pageSize);
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmInterviewDtos.CalendarEntry> calendar(
            @RequestParam String from,
            @RequestParam String to) {
        return ermInterviewService.calendar(Instant.parse(from), Instant.parse(to));
    }

    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmInterviewDtos.ReasonCodeGroup> reasonCodes(
            @RequestParam(required = false) String family) {
        return ermInterviewService.listReasonCodes(family);
    }

    @GetMapping("/eligible-interviewers")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmInterviewDtos.InterviewerView> eligibleInterviewers() {
        return ermInterviewService.listEligibleInterviewers();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN', 'MANAGER', 'TRAINER')")
    public ErmInterviewDtos.ErmInterviewDetail detail(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return ermInterviewService.getDetail(id, caller);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmInterviewDtos.ErmInterviewDetail create(
            @RequestBody ErmInterviewDtos.ErmCreateInterviewRequest req,
            @AuthenticationPrincipal User caller) {
        return ermInterviewService.create(req, caller);
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmInterviewDtos.ErmInterviewDetail reschedule(
            @PathVariable UUID id,
            @RequestBody ErmInterviewDtos.ErmRescheduleRequest req,
            @AuthenticationPrincipal User caller) {
        return ermInterviewService.reschedule(id, req, caller);
    }

    @PostMapping("/{id}/change-interviewer")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmInterviewDtos.ErmInterviewDetail changeInterviewer(
            @PathVariable UUID id,
            @RequestBody ErmInterviewDtos.ChangeInterviewerRequest req,
            @AuthenticationPrincipal User caller) {
        return ermInterviewService.changeInterviewer(id,
                req != null ? req.interviewerId() : null, caller);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmInterviewDtos.ErmInterviewDetail complete(
            @PathVariable UUID id,
            @RequestBody ErmInterviewDtos.ErmCompleteRequest req,
            @AuthenticationPrincipal User caller) {
        return ermInterviewService.complete(id, req, caller);
    }

    /**
     * Regenerate the Zoom meeting attached to this interview. Used when
     * the original Zoom call failed (creds were missing, transient
     * outage) or the link needs to be replaced. Deletes the prior Zoom
     * meeting (best-effort) then creates a fresh one. Returns the
     * updated detail on success or 409 with the Zoom error message if
     * recreation fails.
     */
    @PostMapping("/{id}/zoom/regenerate")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmInterviewDtos.ErmInterviewDetail regenerateZoom(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return ermInterviewService.regenerateZoom(id, caller);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @RequestBody ErmInterviewDtos.ErmCancelRequest req,
            @AuthenticationPrincipal User caller) {
        ermInterviewService.cancel(id, req, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Void> notes(
            @PathVariable UUID id,
            @RequestBody ErmInterviewDtos.NotesRequest req,
            @AuthenticationPrincipal User caller) {
        ermInterviewService.recordNotes(id,
                req != null ? req.applicantVisibleNotes() : null,
                req != null ? req.internalNotes() : null,
                caller);
        return ResponseEntity.noContent().build();
    }
}
