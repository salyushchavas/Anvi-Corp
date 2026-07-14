package com.anvicorp.api.controller;

import com.anvicorp.api.dto.interview.InterviewScorecardSummary;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.InterviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 2.2 — staff-only read endpoint that returns the LATEST submitted
 * scorecard for an application (across all of its interviews), so the
 * recruiter review screen can surface the recommendation + dimension scores
 * without an extra round-trip to list every interview.
 *
 * Lives in its own controller (not on {@code InterviewController}) because
 * the URL is application-scoped, and we don't want to disturb the existing
 * {@code /api/v1/interviews/**} {@code @RequestMapping} root.
 */
@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationScorecardController {

    private final InterviewService interviewService;

    @GetMapping("/{applicationId}/scorecard")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public ResponseEntity<InterviewScorecardSummary> latestScorecard(
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal User caller) {
        InterviewScorecardSummary summary =
                interviewService.findLatestScorecardForApplication(applicationId, caller);
        // 204 keeps callers' code simple — they branch on res.status === 204
        // instead of trying to differentiate "no scorecard" from "no interview".
        if (summary == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(summary);
    }
}
