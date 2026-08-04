package com.anvicorp.api.evaluator.perproject;

import com.anvicorp.api.common.MonthRange;
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

    /** §1 — evaluations awaiting this evaluator's action, sorted oldest-first.
     *  Optional {@code ?month=YYYY-MM} scopes the queue to projects whose
     *  linked {@code completed_at} falls in that month window — current-month
     *  omits the filter so the byte-identical prior queue is returned. */
    @GetMapping("/pending-post-project-evaluations")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public PerProjectDtos.AwaitingEvaluationResponse listAwaiting(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal User caller) {
        return service.listAwaiting(caller, MonthRange.parse(month));
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

    /**
     * §5b — project-first scheduling. Auto-creates a DRAFT POST_PROJECT
     * evaluation if none exists for the project yet, then schedules.
     * Frontend uses this from per-project cards so the Schedule Session
     * button works on ANY project (not just already-auto-drafted ones).
     */
    @PostMapping("/projects/{projectId}/schedule-post-project")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public ResponseEntity<Void> scheduleByProject(
            @PathVariable UUID projectId,
            @RequestBody PerProjectDtos.SchedulePostProjectRequest req,
            @AuthenticationPrincipal User caller) {
        service.scheduleByProject(projectId, req, caller);
        return ResponseEntity.noContent().build();
    }

    /**
     * §4 — schedule ONE Final session covering MULTIPLE POST_PROJECT
     * evaluations at once. All selected rows share one Zoom meeting +
     * scheduledFor + timezone; each advances to SCHEDULED (or
     * IN_PROGRESS when {@code markConducted=true}). Atomic — any
     * validation failure aborts the whole batch.
     */
    @PostMapping("/post-project-evaluations/bulk-schedule")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public PerProjectDtos.BulkScheduleResponse bulkSchedule(
            @RequestBody PerProjectDtos.BulkScheduleRequest req,
            @AuthenticationPrincipal User caller) {
        return service.bulkSchedule(req, caller);
    }

    /**
     * §6 — start EVERY evaluation covered by a shared session in ONE
     * click. Backs the session-strip's Start button on the evaluee
     * detail hub: for a "Schedule Final" that covered two projects,
     * both rows advance to IN_PROGRESS together so the trainer no
     * longer sees mixed Start / Continue states across group members.
     * A group-of-one (single-project session) also works — same call,
     * same semantics, just one row advances.
     */
    @PostMapping("/session-groups/{sessionGroupId}/start")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public java.util.Map<String, Object> startSessionGroup(
            @PathVariable UUID sessionGroupId,
            @AuthenticationPrincipal User caller) {
        java.util.List<UUID> started = service.startSessionGroup(sessionGroupId, caller);
        return java.util.Map.of(
                "sessionGroupId", sessionGroupId,
                "startedEvaluationIds", started,
                "count", started.size());
    }
}
