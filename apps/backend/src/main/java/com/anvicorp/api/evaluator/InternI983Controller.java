package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Evaluator Phase 3 — intern-side surface for viewing + signing I-983
 * evaluations. Mounted at {@code /api/v1/intern/i983-evaluations} so it
 * does NOT collide with the legacy monthly evaluation endpoints at
 * {@code /api/v1/evaluation-cycle} (different controller, different table
 * shape, different ack semantics).
 *
 * <p>Auth: INTERN. Each endpoint scopes by caller.userId.</p>
 */
@RestController
@RequestMapping("/api/v1/intern/i983-evaluations")
@RequiredArgsConstructor
@Slf4j
public class InternI983Controller {

    private final I983EvaluationWorkflowService workflow;

    @GetMapping
    @PreAuthorize("hasRole('INTERN')")
    public List<I983WorkflowDtos.InternI983Row> list(
            @AuthenticationPrincipal User caller) {
        return workflow.listForIntern(caller);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('INTERN')")
    public I983WorkflowDtos.InternI983View get(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return workflow.getInternView(id, caller);
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('INTERN')")
    public I983WorkflowDtos.InternI983View acknowledge(
            @PathVariable UUID id,
            @RequestBody I983WorkflowDtos.AcknowledgeI983Request req,
            @AuthenticationPrincipal User caller) {
        return workflow.acknowledge(id, req, caller);
    }
}
