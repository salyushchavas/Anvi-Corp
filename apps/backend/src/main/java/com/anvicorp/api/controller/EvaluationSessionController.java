package com.anvicorp.api.controller;

import com.anvicorp.api.dto.supervised.AssignEvaluatorRequest;
import com.anvicorp.api.dto.supervised.CompleteEvaluationRequest;
import com.anvicorp.api.dto.supervised.EvaluationSessionResponse;
import com.anvicorp.api.dto.supervised.EvaluatorOption;
import com.anvicorp.api.dto.supervised.InternSummaryResponse;
import com.anvicorp.api.dto.supervised.ScheduleEvaluationRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.EvaluationSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/supervised")
@RequiredArgsConstructor
public class EvaluationSessionController {

    private final EvaluationSessionService evaluationSessionService;

    @GetMapping("/evaluators")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public List<EvaluatorOption> listEvaluators() {
        return evaluationSessionService.listEvaluators();
    }

    @PostMapping("/interns/{candidateId}/assign-evaluator")
    @PreAuthorize("hasRole('ERM')")
    public InternSummaryResponse assignEvaluator(
            @PathVariable UUID candidateId,
            @Valid @RequestBody AssignEvaluatorRequest req) {
        return evaluationSessionService.assignEvaluator(candidateId, req);
    }

    @PostMapping("/interns/{candidateId}/evaluations")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public ResponseEntity<EvaluationSessionResponse> schedule(
            @PathVariable UUID candidateId,
            @Valid @RequestBody ScheduleEvaluationRequest req) {
        EvaluationSessionResponse created = evaluationSessionService.schedule(candidateId, req);
        return ResponseEntity.created(URI.create("/api/v1/supervised/evaluations/" + created.getId()))
                .body(created);
    }

    @GetMapping("/interns/{candidateId}/evaluations")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public List<EvaluationSessionResponse> listForIntern(@PathVariable UUID candidateId) {
        return evaluationSessionService.listForIntern(candidateId);
    }

    @PostMapping("/evaluations/{id}/complete")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public EvaluationSessionResponse complete(@PathVariable UUID id,
                                              @Valid @RequestBody CompleteEvaluationRequest req) {
        return evaluationSessionService.complete(id, req);
    }

    @PostMapping("/evaluations/{id}/miss")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public EvaluationSessionResponse miss(@PathVariable UUID id) {
        return evaluationSessionService.miss(id);
    }

    @GetMapping("/my/evaluations")
    @PreAuthorize("hasRole('INTERN')")
    public List<EvaluationSessionResponse> listMine(@AuthenticationPrincipal User caller) {
        return evaluationSessionService.listForCandidateUser(caller);
    }
}
