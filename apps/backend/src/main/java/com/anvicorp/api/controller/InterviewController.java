package com.anvicorp.api.controller;

import com.anvicorp.api.dto.interview.CandidateInterviewResponse;
import com.anvicorp.api.dto.interview.InterviewResponse;
import com.anvicorp.api.dto.interview.InterviewScorecardSummary;
import com.anvicorp.api.dto.interview.InterviewSummaryResponse;
import com.anvicorp.api.dto.interview.ScheduleInterviewRequest;
import com.anvicorp.api.dto.interview.SubmitFeedbackRequest;
import com.anvicorp.api.dto.interview.SubmitScorecardRequest;
import com.anvicorp.api.dto.interview.UpdateInterviewRequest;
import com.anvicorp.api.dto.interview.UpdateStatusRequest;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.service.InterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @PostMapping
    @PreAuthorize("hasRole('ERM')")
    public ResponseEntity<InterviewResponse> schedule(
            @Valid @RequestBody ScheduleInterviewRequest req,
            @AuthenticationPrincipal User user) {
        InterviewResponse created = interviewService.schedule(req, user);
        return ResponseEntity.created(URI.create("/api/v1/interviews/" + created.getId()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public PagedResponse<InterviewSummaryResponse> list(
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) InterviewStatus status,
            @RequestParam(required = false) UUID interviewerId,
            @RequestParam(required = false) Boolean upcoming,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Sort sort = Boolean.FALSE.equals(upcoming)
                ? Sort.by(Sort.Direction.DESC, "scheduledAt")
                : Sort.by(Sort.Direction.ASC, "scheduledAt");
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                sort);
        return PagedResponse.of(
                interviewService.list(applicationId, status, interviewerId, upcoming, pageable));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public List<CandidateInterviewResponse> listMine(@AuthenticationPrincipal User user) {
        return interviewService.listForCandidate(user);
    }

    /** Phase 2 — doc-spec alias for {@code /me}. */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('INTERN')")
    public List<CandidateInterviewResponse> listMineAlias(@AuthenticationPrincipal User user) {
        return interviewService.listForCandidate(user);
    }

    /**
     * Field-level RBAC: when an INTERN owns this interview, return the
     * applicant-safe DTO (no zoomStartUrl, no internalNotes). Staff callers
     * (ERM / TRAINER / MANAGER / SUPER_ADMIN) get the full
     * {@link InterviewResponse}. The controller exposes both shapes via
     * polymorphic {@code Object} so the same path works for both audiences.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'TRAINER', 'MANAGER', 'SUPER_ADMIN')")
    public Object getOne(@PathVariable UUID id,
                         @AuthenticationPrincipal User user) {
        boolean staff = user != null && (
                user.getRoles().contains(com.anvicorp.api.enums.UserRole.ERM)
                || user.getRoles().contains(com.anvicorp.api.enums.UserRole.TRAINER)
                || user.getRoles().contains(com.anvicorp.api.enums.UserRole.MANAGER)
                || user.getRoles().contains(com.anvicorp.api.enums.UserRole.SUPER_ADMIN));
        if (staff) {
            return interviewService.getDetail(id, user);
        }
        // Intern path: applicant-safe view — service still does the ownership
        // check so a non-owner intern gets 403.
        return interviewService.getDetailForCandidate(id, user);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    public InterviewResponse update(@PathVariable UUID id,
                                    @Valid @RequestBody UpdateInterviewRequest req,
                                    @AuthenticationPrincipal User user) {
        return interviewService.update(id, req, user);
    }

    @PostMapping("/{id}/feedback")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public InterviewResponse submitFeedback(@PathVariable UUID id,
                                            @Valid @RequestBody SubmitFeedbackRequest req,
                                            @AuthenticationPrincipal User user) {
        // Legacy freeform path — kept for backward compatibility with any
        // pre-2.2 clients. New code should POST to /scorecard.
        return interviewService.submitFeedback(id, req, user);
    }

    /**
     * Phase 2.2 — structured scorecard. Same auth shape as /feedback but with
     * required problemSolvingRating + structured comments, and validated
     * bounds (1-5) on each dimension. Idempotent: same interviewer can resubmit.
     */
    @PostMapping("/{id}/scorecard")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public InterviewResponse submitScorecard(@PathVariable UUID id,
                                             @Valid @RequestBody SubmitScorecardRequest req,
                                             @AuthenticationPrincipal User user) {
        return interviewService.submitScorecard(id, req, user);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ERM')")
    public InterviewResponse updateStatus(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateStatusRequest req,
                                          @AuthenticationPrincipal User user) {
        return interviewService.updateStatus(id, req.getStatus(), user);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal User user) {
        interviewService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    // ── Phase 2 doc-spec commands ───────────────────────────────────────────

    /** Reschedule a SCHEDULED interview. Updates the Zoom meeting in place. */
    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public InterviewResponse reschedule(
            @PathVariable UUID id,
            @Valid @RequestBody com.anvicorp.api.dto.interview.RescheduleInterviewRequest req,
            @AuthenticationPrincipal User user) {
        return interviewService.reschedule(id, req, user);
    }

    /**
     * Complete an interview with a doc-spec decision + applicant-safe note.
     * Advances the application + applicant lifecycle in the same transaction.
     */
    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public InterviewResponse complete(
            @PathVariable UUID id,
            @Valid @RequestBody com.anvicorp.api.dto.interview.CompleteInterviewRequest req,
            @AuthenticationPrincipal User user) {
        return interviewService.complete(id, req, user);
    }

    /** Cancel a SCHEDULED interview. Deletes the Zoom meeting best-effort. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public InterviewResponse cancel(@PathVariable UUID id,
                                    @AuthenticationPrincipal User user) {
        return interviewService.cancel(id, user);
    }
}
