package com.anvicorp.api.controller;

import com.anvicorp.api.dto.evaluation.CreateEvaluationRequest;
import com.anvicorp.api.dto.evaluation.EvaluationResponse;
import com.anvicorp.api.dto.evaluation.SubmitSelfReviewRequest;
import com.anvicorp.api.dto.evaluation.UpdateEvaluationRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.EvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Periodic evaluation endpoints.
 *
 * <h2>Supervisor (TECHNICAL_EVALUATOR, SUPER_ADMIN)</h2>
 * <ul>
 *   <li>POST   /evaluations</li>
 *   <li>PUT    /evaluations/{id}                  — blocked when FINALIZED</li>
 *   <li>POST   /evaluations/{id}/finalize</li>
 *   <li>GET    /evaluations/authored              — my authored evaluations</li>
 * </ul>
 *
 * <h2>Read — supervisor / HR / SUPER_ADMIN</h2>
 * <ul>
 *   <li>GET    /evaluations/intern/{candidateId}</li>
 * </ul>
 *
 * <h2>Intern</h2>
 * <ul>
 *   <li>GET    /evaluations/me                    — FINALIZED rows only</li>
 *   <li>PUT    /evaluations/{id}/self             — self-review for I-983 types</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/evaluations")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService service;

    // ── Supervisor write ────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ResponseEntity<EvaluationResponse> create(
            @Valid @RequestBody CreateEvaluationRequest req,
            @AuthenticationPrincipal User user) {
        EvaluationResponse created = service.create(req, user);
        return ResponseEntity.created(URI.create("/api/v1/evaluations/" + created.getId()))
                .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public EvaluationResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEvaluationRequest req,
            @AuthenticationPrincipal User user) {
        return service.update(id, req, user);
    }

    @PostMapping("/{id}/finalize")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public EvaluationResponse finalizeEvaluation(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return service.finalize(id, user);
    }

    /** Author's board — all evaluations this supervisor has written. */
    @GetMapping("/authored")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<EvaluationResponse> listAuthored(@AuthenticationPrincipal User user) {
        return service.listAuthored(user);
    }

    // ── Read — supervisor / HR / SUPER_ADMIN ────────────────────────────────

    @GetMapping("/intern/{candidateId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public List<EvaluationResponse> listForIntern(
            @PathVariable UUID candidateId,
            @AuthenticationPrincipal User user) {
        return service.listForIntern(candidateId, user);
    }

    // ── Intern ──────────────────────────────────────────────────────────────

    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public List<EvaluationResponse> listMine(@AuthenticationPrincipal User user) {
        return service.listMine(user);
    }

    /** Intern's DRAFT I-983 evals — self-review entry surface. */
    @GetMapping("/me/self-reviewable")
    @PreAuthorize("hasRole('INTERN')")
    public List<EvaluationResponse> listSelfReviewable(@AuthenticationPrincipal User user) {
        return service.listSelfReviewable(user);
    }

    @PutMapping("/{id}/self")
    @PreAuthorize("hasRole('INTERN')")
    public EvaluationResponse submitSelfReview(
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitSelfReviewRequest req,
            @AuthenticationPrincipal User user) {
        return service.submitSelfReview(id, req, user);
    }
}
