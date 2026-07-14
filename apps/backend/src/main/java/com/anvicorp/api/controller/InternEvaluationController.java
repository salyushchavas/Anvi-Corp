package com.anvicorp.api.controller;

import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.intern.InternEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6 evaluation surface. Mounted at {@code /api/v1/evaluation-cycle}
 * to avoid colliding with the legacy {@code /api/v1/evaluations} controller
 * (DRAFT → FINALIZED supervisor-author model).
 */
@RestController
@RequestMapping("/api/v1/evaluation-cycle")
@RequiredArgsConstructor
public class InternEvaluationController {

    private final InternEvaluationService service;

    // ── Evaluator ─────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                       @AuthenticationPrincipal User actor) {
        UUID lcId = uuid(body, "internLifecycleId");
        String type = str(body, "evaluationType");
        LocalDate periodStart = optDate(body, "periodStart");
        LocalDate periodEnd = optDate(body, "periodEnd");
        UUID linkedProjectId = optUuid(body, "linkedProjectId");
        UUID linkedI983Id = optUuid(body, "linkedI983Id");
        return staffDto(service.create(lcId, type, periodStart, periodEnd,
                linkedProjectId, linkedI983Id, actor));
    }

    @PostMapping("/{id}/schedule")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> schedule(@PathVariable UUID id,
                                         @RequestBody Map<String, Object> body,
                                         @AuthenticationPrincipal User actor) {
        Instant scheduledFor = Instant.parse(str(body, "scheduledFor"));
        Integer duration = optInt(body, "durationMinutes");
        String timezone = (String) body.get("timezone");
        return staffDto(service.schedule(id, scheduledFor, duration, timezone, actor));
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> reschedule(@PathVariable UUID id,
                                           @RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal User actor) {
        Instant newScheduledFor = Instant.parse(str(body, "scheduledFor"));
        Integer duration = optInt(body, "durationMinutes");
        return staffDto(service.reschedule(id, newScheduledFor, duration, actor));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> start(@PathVariable UUID id,
                                      @AuthenticationPrincipal User actor) {
        return staffDto(service.start(id, actor));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> update(@PathVariable UUID id,
                                       @RequestBody Map<String, Object> body,
                                       @AuthenticationPrincipal User actor) {
        return staffDto(service.update(id, body, actor));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> publish(@PathVariable UUID id,
                                        @AuthenticationPrincipal User actor) {
        return staffDto(service.publish(id, actor));
    }

    @PostMapping("/{id}/amend")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> amend(@PathVariable UUID id,
                                      @RequestBody Map<String, String> body,
                                      @AuthenticationPrincipal User actor) {
        String reason = body == null ? null : body.get("amendmentReason");
        return staffDto(service.amend(id, reason, actor));
    }

    @PostMapping("/{id}/republish")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> republish(@PathVariable UUID id,
                                          @AuthenticationPrincipal User actor) {
        return staffDto(service.republish(id, actor));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> cancel(@PathVariable UUID id,
                                       @AuthenticationPrincipal User actor) {
        return staffDto(service.cancel(id, actor));
    }

    @GetMapping("/evaluator/queue")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public List<Map<String, Object>> evaluatorQueue(@AuthenticationPrincipal User actor) {
        return service.listForEvaluator(actor.getId()).stream()
                .map(InternEvaluationController::staffDto)
                .toList();
    }

    // ── Intern ────────────────────────────────────────────────────────────

    @GetMapping("/mine")
    @PreAuthorize("hasRole('INTERN')")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal User caller) {
        return service.listForIntern(caller.getId()).stream()
                .map(InternEvaluationController::internSafeDto)
                .toList();
    }

    @GetMapping("/upcoming")
    @PreAuthorize("hasRole('INTERN')")
    public List<Map<String, Object>> upcoming(@AuthenticationPrincipal User caller) {
        return service.listUpcomingForIntern(caller.getId()).stream()
                .map(InternEvaluationController::internUpcomingDto)
                .toList();
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> acknowledge(@PathVariable UUID id,
                                            @RequestBody(required = false) Map<String, String> body,
                                            @AuthenticationPrincipal User caller) {
        String response = body == null ? null : body.get("response");
        return internSafeDto(service.acknowledge(id, response, caller));
    }

    // ── Shared read with field-level RBAC ─────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'TRAINER', 'REPORTING_MANAGER', 'MANAGER', 'ERM', 'SUPER_ADMIN')")
    public Map<String, Object> getOne(@PathVariable UUID id,
                                       @AuthenticationPrincipal User caller) {
        InternEvaluation eval = service.getOne(id);
        boolean staff = caller.getRoles().contains(UserRole.TRAINER)
                || caller.getRoles().contains(UserRole.REPORTING_MANAGER)
                || caller.getRoles().contains(UserRole.MANAGER)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (staff) return staffDto(eval);
        // INTERN: must own AND status must be intern-visible.
        if (!caller.getId().equals(eval.getInternId())) {
            throw new ForbiddenException("Not allowed to view this evaluation");
        }
        if (!"PUBLISHED".equals(eval.getStatus())
                && !"ACKNOWLEDGED".equals(eval.getStatus())
                && !"AMENDED".equals(eval.getStatus())) {
            throw new ForbiddenException("Evaluation not yet published");
        }
        return internSafeDto(eval);
    }

    @GetMapping("/intern/{userId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'MANAGER', 'ERM', 'SUPER_ADMIN')")
    public List<Map<String, Object>> forIntern(@PathVariable UUID userId) {
        return service.listForIntern(userId).stream()
                .map(InternEvaluationController::staffDto)
                .toList();
    }

    // ── DTO shapers ───────────────────────────────────────────────────────

    /** Staff DTO — includes internal_notes + zoom_start_url. */
    private static Map<String, Object> staffDto(InternEvaluation e) {
        Map<String, Object> m = baseDto(e);
        m.put("zoomStartUrl", e.getZoomStartUrl());
        m.put("internalNotes", e.getInternalNotes());
        return m;
    }

    /**
     * Intern-safe DTO — by construction excludes {@code zoomStartUrl} and
     * {@code internalNotes}. Adding host/staff state here is a bug.
     */
    private static Map<String, Object> internSafeDto(InternEvaluation e) {
        return baseDto(e);
    }

    /**
     * Pre-publish "upcoming" view — only the meeting-essential fields so
     * the intern's hero card can render without leaking scores/narratives.
     */
    private static Map<String, Object> internUpcomingDto(InternEvaluation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("evaluationType", e.getEvaluationType());
        m.put("scheduledFor", e.getScheduledFor());
        m.put("durationMinutes", e.getDurationMinutes());
        m.put("timezone", e.getTimezone());
        m.put("zoomJoinUrl", e.getZoomJoinUrl());
        m.put("zoomPassword", e.getZoomPassword());
        return m;
    }

    private static Map<String, Object> baseDto(InternEvaluation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("internLifecycleId", e.getInternLifecycleId());
        m.put("internId", e.getInternId());
        m.put("evaluatorId", e.getEvaluatorId());
        m.put("evaluationType", e.getEvaluationType());
        m.put("linkedProjectId", e.getLinkedProjectId());
        m.put("linkedI983Id", e.getLinkedI983Id());
        m.put("periodStart", e.getPeriodStart());
        m.put("periodEnd", e.getPeriodEnd());
        m.put("scheduledFor", e.getScheduledFor());
        m.put("durationMinutes", e.getDurationMinutes());
        m.put("timezone", e.getTimezone());
        m.put("zoomMeetingId", e.getZoomMeetingId());
        m.put("zoomJoinUrl", e.getZoomJoinUrl());
        m.put("zoomPassword", e.getZoomPassword());
        m.put("status", e.getStatus());
        m.put("overallScore", e.getOverallScore());
        m.put("technicalSkillsScore", e.getTechnicalSkillsScore());
        m.put("communicationScore", e.getCommunicationScore());
        m.put("professionalismScore", e.getProfessionalismScore());
        m.put("learningApplicationScore", e.getLearningApplicationScore());
        m.put("strengthsNarrative", e.getStrengthsNarrative());
        m.put("areasForImprovementNarrative", e.getAreasForImprovementNarrative());
        m.put("improvementPlan", e.getImprovementPlan());
        m.put("internAcknowledgedAt", e.getInternAcknowledgedAt());
        m.put("internResponse", e.getInternResponse());
        m.put("publishedAt", e.getPublishedAt());
        m.put("amendedAt", e.getAmendedAt());
        m.put("amendmentReason", e.getAmendmentReason());
        m.put("version", e.getVersion());
        return m;
    }

    // ── Body parsers ──────────────────────────────────────────────────────

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new BadRequestException(key + " is required");
        }
        return v.toString();
    }

    private static UUID uuid(Map<String, Object> body, String key) {
        return UUID.fromString(str(body, key));
    }

    private static UUID optUuid(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || v.toString().isBlank()) return null;
        return UUID.fromString(v.toString());
    }

    private static Integer optInt(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private static LocalDate optDate(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || v.toString().isBlank()) return null;
        return LocalDate.parse(v.toString());
    }
}
