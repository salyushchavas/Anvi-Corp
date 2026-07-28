package com.anvicorp.api.evaluator.perproject;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Evaluator per-project HTTP surface — parallels the existing
 * monthly-cycle endpoints on {@code EvaluatorController}. Enables the
 * "Projects Awaiting Evaluation" queue, the Active Evaluee project
 * timeline, and the per-project context hub (project + weekly reports
 * + per-project schedule).
 */
@RestController
@RequestMapping("/api/v1/evaluator")
@RequiredArgsConstructor
public class PerProjectController {

    private final PerProjectService service;

    /** §1 — evaluations awaiting this evaluator's action, sorted oldest-first. */
    @GetMapping("/pending-post-project-evaluations")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public PerProjectDtos.AwaitingEvaluationResponse listAwaiting(
            @AuthenticationPrincipal User caller) {
        return service.listAwaiting(caller);
    }

    /** §3 — every project for this intern + evaluation status per project. */
    @GetMapping("/evaluees/{lifecycleId}/project-timeline")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public PerProjectDtos.ProjectTimelineResponse timeline(
            @PathVariable UUID lifecycleId,
            @AuthenticationPrincipal User caller) {
        return service.getInternTimeline(lifecycleId, caller);
    }

    /** §4 — project detail for the context hub's project panel. */
    @GetMapping("/projects/{projectId}")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public PerProjectDtos.ProjectContext projectContext(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal User caller) {
        return service.getProjectContext(projectId, caller);
    }

    /** §4 — recent weekly reports for an intern (default limit 8, max 20). */
    @GetMapping("/evaluees/{lifecycleId}/weekly-reports")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public PerProjectDtos.WeeklyReportsResponse weeklyReports(
            @PathVariable UUID lifecycleId,
            @RequestParam(defaultValue = "8") int limit,
            @AuthenticationPrincipal User caller) {
        return service.getInternWeeklyReports(lifecycleId, caller, limit);
    }

    /** §5 — schedule a session on an existing POST_PROJECT evaluation row. */
    @PostMapping("/post-project-evaluations/{evaluationId}/schedule")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public ResponseEntity<Void> schedulePostProject(
            @PathVariable UUID evaluationId,
            @RequestBody PerProjectDtos.SchedulePostProjectRequest req,
            @AuthenticationPrincipal User caller) {
        service.schedulePostProject(evaluationId, req, caller);
        return ResponseEntity.noContent().build();
    }
}
