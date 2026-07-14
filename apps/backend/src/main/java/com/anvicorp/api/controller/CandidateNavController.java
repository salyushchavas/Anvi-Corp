package com.anvicorp.api.controller;

import com.anvicorp.api.dto.nav.CandidateNavResponse;
import com.anvicorp.api.dto.nav.MarkNavSeenRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.CandidateNavService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backend-driven sidebar for the APPLICANT / INTERN face. Staff sidebars
 * (HR, OPERATIONS, TECHNICAL_EVALUATOR, EXECUTIVE, SUPER_ADMIN)
 * remain frontend-hardcoded — this endpoint is candidate-only.
 *
 * <ul>
 *   <li>{@code GET /api/v1/candidate/nav} — returns the items the caller
 *       should see right now, ordered, grouped (for interns), badged.</li>
 *   <li>{@code POST /api/v1/candidate/nav/seen} — frontend calls this when
 *       the user opens a route that had a "new" badge, dismissing it.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/candidate/nav")
@RequiredArgsConstructor
public class CandidateNavController {

    private final CandidateNavService navService;

    @GetMapping
    @PreAuthorize("hasRole('INTERN')")
    public CandidateNavResponse getNav(@AuthenticationPrincipal User user) {
        return navService.build(user);
    }

    @PostMapping("/seen")
    @PreAuthorize("hasRole('INTERN')")
    public ResponseEntity<Void> markSeen(@Valid @RequestBody MarkNavSeenRequest req,
                                         @AuthenticationPrincipal User user) {
        navService.markSeen(user, req.key());
        return ResponseEntity.noContent().build();
    }
}
