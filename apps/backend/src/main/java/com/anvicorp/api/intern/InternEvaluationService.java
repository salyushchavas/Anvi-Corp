package com.anvicorp.api.intern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.EvaluationAmendment;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.event.evaluation.EvaluationAmendedEvent;
import com.anvicorp.api.event.evaluation.EvaluationDraftedEvent;
import com.anvicorp.api.event.evaluation.EvaluationPublishedEvent;
import com.anvicorp.api.event.evaluation.EvaluationScheduledEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.EvaluationAmendmentRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6 — evaluation cycle orchestration. Owns the DRAFT → SCHEDULED →
 * IN_PROGRESS → PUBLISHED → ACKNOWLEDGED → AMENDED lifecycle with scope
 * guards (caller must be the lifecycle's evaluator unless SUPER_ADMIN),
 * Zoom integration on schedule/reschedule/cancel, publish-gate validation,
 * and an audited amendment workflow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternEvaluationService {

    private static final Set<String> VALID_TYPES = Set.of(
            "MONTHLY", "POST_PROJECT", "STEM_OPT_12_MONTH", "STEM_OPT_24_MONTH", "FINAL");

    private static final Set<String> EDITABLE_STATUSES = Set.of(
            "DRAFT", "IN_PROGRESS", "PUBLISHED"); // PUBLISHED editable only between /amend and /republish

    private final InternEvaluationRepository evalRepository;
    private final EvaluationAmendmentRepository amendmentRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final MeetingProvider meetingProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.anvicorp.api.service.LifecycleAccessPolicy lifecycleAccessPolicy;
    private final com.anvicorp.api.config.BrandConfig brand;
    private final OrgTeamResolver orgTeamResolver;

    // ── Evaluator commands ────────────────────────────────────────────────

    @Transactional
    public InternEvaluation create(UUID internLifecycleId, String evaluationType,
                                    LocalDate periodStart, LocalDate periodEnd,
                                    UUID linkedProjectId, UUID linkedI983Id,
                                    User actor) {
        if (evaluationType == null || !VALID_TYPES.contains(evaluationType)) {
            throw new BadRequestException("evaluationType must be one of " + VALID_TYPES);
        }
        InternLifecycle lc = lifecycleRepository.findById(internLifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + internLifecycleId));
        ensureEvaluatorScope(lc, actor);
        ensureLifecycleActive(lc);

        if ("POST_PROJECT".equals(evaluationType) && linkedProjectId == null) {
            throw new BadRequestException("linkedProjectId is required for POST_PROJECT");
        }
        if ("STEM_OPT_12_MONTH".equals(evaluationType) && linkedI983Id == null) {
            throw new BadRequestException("linkedI983Id is required for STEM_OPT_12_MONTH");
        }
        if ("STEM_OPT_24_MONTH".equals(evaluationType) && linkedI983Id == null) {
            throw new BadRequestException("linkedI983Id is required for STEM_OPT_24_MONTH");
        }
        if ("MONTHLY".equals(evaluationType) && (periodStart == null || periodEnd == null)) {
            throw new BadRequestException("periodStart and periodEnd required for MONTHLY");
        }

        InternEvaluation eval = InternEvaluation.builder()
                .internLifecycleId(lc.getId())
                .internId(lc.getUserId())
                .evaluatorId(actor.getId())
                .evaluationType(evaluationType)
                .linkedProjectId(linkedProjectId)
                .linkedI983Id(linkedI983Id)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .status("DRAFT")
                .version(1)
                .build();
        eval = evalRepository.save(eval);
        writeAudit(eval, "CREATE", actor.getId());
        return eval;
    }

    /**
     * Auto-draft path used by the post-project listener. Allows the system
     * to create rows without an interactive Evaluator session.
     *
     * <p>Resolves the evaluator through {@link OrgTeamResolver} — the
     * per-intern {@code evaluator_id} FK is a hint, not a requirement.
     * When it's null (single-evaluator-org default), fall through to the
     * configured {@code app.default-evaluator-email} instead of throwing.
     * This aligns the drafter with the guard + listener resolution path
     * so any COMPLETED project on a null-FK lifecycle still gets a row
     * (the picker can only self-heal from a row that exists). Throws
     * {@link IllegalStateException} only when even the fallback is
     * unresolvable — callers wrap that in a WARN, never silent.</p>
     */
    @Transactional
    public InternEvaluation autoDraftPostProject(InternLifecycle lc,
                                                  UUID projectId,
                                                  UUID actorIdIfAny) {
        UUID evaluatorId = orgTeamResolver.resolveEvaluatorId(lc);
        if (evaluatorId == null) {
            throw new IllegalStateException(
                    "No evaluator resolvable for lifecycle "
                            + (lc != null ? lc.getId() : null)
                            + " (per-intern evaluator_id null AND "
                            + "app.default-evaluator-email unset / unresolvable)");
        }
        InternEvaluation eval = InternEvaluation.builder()
                .internLifecycleId(lc.getId())
                .internId(lc.getUserId())
                .evaluatorId(evaluatorId)
                .evaluationType("POST_PROJECT")
                .linkedProjectId(projectId)
                .status("DRAFT")
                .version(1)
                .build();
        eval = evalRepository.save(eval);
        writeAudit(eval, "AUTO_DRAFT", actorIdIfAny);
        try {
            eventPublisher.publishEvent(new EvaluationDraftedEvent(
                    eval.getId(), eval.getEvaluatorId(), eval.getEvaluationType(), projectId));
        } catch (Exception e) {
            log.warn("EvaluationDraftedEvent publish failed (non-fatal): {}", e.getMessage());
        }
        return eval;
    }

    @Transactional
    public InternEvaluation schedule(UUID evalId, Instant scheduledFor,
                                      Integer durationMinutes, String timezone, User actor) {
        InternEvaluation eval = mustGet(evalId);
        InternLifecycle lc = mustGetLifecycle(eval.getInternLifecycleId());
        ensureEvaluatorScope(lc, actor);
        ensureLifecycleActive(lc);
        if (!"DRAFT".equals(eval.getStatus()) && !"SCHEDULED".equals(eval.getStatus())) {
            throw new ConflictException(
                    "Can only schedule from DRAFT or SCHEDULED (current: " + eval.getStatus() + ")");
        }
        if (scheduledFor == null || scheduledFor.isBefore(Instant.now())) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        int duration = durationMinutes == null ? 30
                : Math.max(15, Math.min(120, durationMinutes));
        String tz = (timezone == null || timezone.isBlank()) ? "UTC" : timezone;

        eval.setScheduledFor(scheduledFor);
        eval.setDurationMinutes(duration);
        eval.setTimezone(tz);

        if (meetingProvider.isReady()) {
            try {
                String hostId = actor.getZoomEmail() != null && !actor.getZoomEmail().isBlank()
                        ? actor.getZoomEmail() : "me";
                String topic = brand.getName() + " evaluation — " + eval.getEvaluationType();
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(hostId, topic, scheduledFor, duration, tz, null));
                eval.setZoomMeetingId(z.providerMeetingId());
                eval.setZoomJoinUrl(z.joinUrl());
                eval.setZoomStartUrl(z.startUrl());
                eval.setZoomPassword(z.password());
                log.info("[Evaluation] Zoom created id={} for eval={}",
                        z.providerMeetingId(), eval.getId());
            } catch (Exception e) {
                log.warn("[Evaluation] Zoom create failed (non-fatal) for {}: {}",
                        eval.getId(), e.getMessage());
            }
        }
        eval.setStatus("SCHEDULED");
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "SCHEDULE", actor.getId());
        try {
            eventPublisher.publishEvent(new EvaluationScheduledEvent(
                    saved.getId(), saved.getInternId(), saved.getEvaluatorId()));
        } catch (Exception e) {
            log.warn("EvaluationScheduledEvent publish failed: {}", e.getMessage());
        }
        return saved;
    }

    @Transactional
    public InternEvaluation reschedule(UUID evalId, Instant newScheduledFor,
                                        Integer newDurationMinutes, User actor) {
        InternEvaluation eval = mustGet(evalId);
        ensureEvaluatorScope(mustGetLifecycle(eval.getInternLifecycleId()), actor);
        if (!"SCHEDULED".equals(eval.getStatus())) {
            throw new ConflictException("Only SCHEDULED evaluations can be rescheduled");
        }
        if (newScheduledFor == null || newScheduledFor.isBefore(Instant.now())) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        eval.setScheduledFor(newScheduledFor);
        if (newDurationMinutes != null) {
            eval.setDurationMinutes(Math.max(15, Math.min(120, newDurationMinutes)));
        }
        if (eval.getZoomMeetingId() != null && meetingProvider.isReady()) {
            try {
                meetingProvider.updateMeeting(eval.getZoomMeetingId(),
                        new MeetingRequest(null,
                                brand.getName() + " evaluation — " + eval.getEvaluationType(),
                                eval.getScheduledFor(), eval.getDurationMinutes(),
                                eval.getTimezone(), null));
            } catch (Exception e) {
                log.warn("[Evaluation] Zoom update failed: {}", e.getMessage());
            }
        }
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "RESCHEDULE", actor.getId());
        return saved;
    }

    @Transactional
    public InternEvaluation start(UUID evalId, User actor) {
        InternEvaluation eval = mustGet(evalId);
        ensureEvaluatorScope(mustGetLifecycle(eval.getInternLifecycleId()), actor);
        if (!"SCHEDULED".equals(eval.getStatus())) {
            throw new ConflictException("Can only start from SCHEDULED");
        }
        // Allow start any time within 15 minutes before scheduled_for or after.
        if (eval.getScheduledFor() != null
                && Instant.now().isBefore(eval.getScheduledFor().minus(Duration.ofMinutes(15)))) {
            throw new ConflictException("Cannot start earlier than 15 min before scheduled time");
        }
        eval.setStatus("IN_PROGRESS");
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "START", actor.getId());
        return saved;
    }

    @Transactional
    public InternEvaluation update(UUID evalId, Map<String, Object> body, User actor) {
        InternEvaluation eval = mustGet(evalId);
        ensureEvaluatorScope(mustGetLifecycle(eval.getInternLifecycleId()), actor);
        if (!EDITABLE_STATUSES.contains(eval.getStatus())) {
            throw new ConflictException(
                    "Evaluation not editable in status " + eval.getStatus());
        }
        applyScores(eval, body);
        applyText(eval, body, "strengthsNarrative", eval::setStrengthsNarrative);
        applyText(eval, body, "areasForImprovementNarrative",
                eval::setAreasForImprovementNarrative);
        applyText(eval, body, "improvementPlan", eval::setImprovementPlan);
        applyText(eval, body, "internalNotes", eval::setInternalNotes);
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "UPDATE", actor.getId());
        return saved;
    }

    @Transactional
    public InternEvaluation publish(UUID evalId, User actor) {
        InternEvaluation eval = mustGet(evalId);
        ensureEvaluatorScope(mustGetLifecycle(eval.getInternLifecycleId()), actor);
        if (!"IN_PROGRESS".equals(eval.getStatus())) {
            throw new ConflictException("Can only publish from IN_PROGRESS");
        }
        validatePublishGate(eval);
        eval.setStatus("PUBLISHED");
        eval.setPublishedAt(Instant.now());
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "PUBLISH", actor.getId());
        try {
            eventPublisher.publishEvent(new EvaluationPublishedEvent(
                    saved.getId(), saved.getInternId(), saved.getEvaluatorId(),
                    saved.getEvaluationType()));
        } catch (Exception e) {
            log.warn("EvaluationPublishedEvent publish failed: {}", e.getMessage());
        }
        return saved;
    }

    @Transactional
    public InternEvaluation amend(UUID evalId, String reason, User actor) {
        if (reason == null || reason.trim().length() < 30) {
            throw new BadRequestException("amendmentReason must be at least 30 characters");
        }
        InternEvaluation eval = mustGet(evalId);
        ensureEvaluatorScope(mustGetLifecycle(eval.getInternLifecycleId()), actor);
        // Phase 8: amendments to PUBLISHED evals on an exited intern are
        // RESOLVE_EXISTING — allowed inside the 30-day cleanup window.
        lifecycleAccessPolicy.ensureCanWrite(actor, eval.getInternId(),
                com.anvicorp.api.service.LifecycleAccessPolicy.WriteIntent.RESOLVE_EXISTING);
        if (!"PUBLISHED".equals(eval.getStatus())
                && !"ACKNOWLEDGED".equals(eval.getStatus())
                && !"AMENDED".equals(eval.getStatus())) {
            throw new ConflictException(
                    "Can only amend from PUBLISHED / ACKNOWLEDGED / AMENDED");
        }
        int prevVersion = eval.getVersion();
        // Snapshot prior state for audit.
        try {
            EvaluationAmendment amendment = EvaluationAmendment.builder()
                    .evaluationId(eval.getId())
                    .amendedById(actor.getId())
                    .amendmentReason(reason.trim())
                    .previousVersion(prevVersion)
                    .newVersion(prevVersion + 1)
                    .snapshotJson(objectMapper.writeValueAsString(toSnapshot(eval)))
                    .amendedAt(Instant.now())
                    .build();
            amendmentRepository.save(amendment);
        } catch (Exception e) {
            log.warn("[Evaluation] amendment snapshot serialization failed: {}", e.getMessage());
        }
        eval.setVersion(prevVersion + 1);
        eval.setAmendmentReason(reason.trim());
        // Re-open for edits — status stays PUBLISHED and the editable guard
        // allows mutation; /republish flips to AMENDED.
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "AMEND_OPEN", actor.getId());
        return saved;
    }

    @Transactional
    public InternEvaluation republish(UUID evalId, User actor) {
        InternEvaluation eval = mustGet(evalId);
        ensureEvaluatorScope(mustGetLifecycle(eval.getInternLifecycleId()), actor);
        if (eval.getAmendmentReason() == null || eval.getAmendmentReason().isBlank()) {
            throw new ConflictException("No active amendment to republish");
        }
        validatePublishGate(eval);
        eval.setStatus("AMENDED");
        eval.setAmendedAt(Instant.now());
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "REPUBLISH", actor.getId());
        try {
            eventPublisher.publishEvent(new EvaluationAmendedEvent(
                    saved.getId(), saved.getInternId(), saved.getEvaluatorId(),
                    saved.getVersion()));
        } catch (Exception e) {
            log.warn("EvaluationAmendedEvent publish failed: {}", e.getMessage());
        }
        return saved;
    }

    @Transactional
    public InternEvaluation cancel(UUID evalId, User actor) {
        InternEvaluation eval = mustGet(evalId);
        ensureEvaluatorScope(mustGetLifecycle(eval.getInternLifecycleId()), actor);
        if (!"DRAFT".equals(eval.getStatus()) && !"SCHEDULED".equals(eval.getStatus())) {
            throw new ConflictException(
                    "Cannot cancel from status " + eval.getStatus());
        }
        if (eval.getZoomMeetingId() != null && meetingProvider.isReady()) {
            try {
                meetingProvider.deleteMeeting(eval.getZoomMeetingId());
            } catch (Exception e) {
                log.warn("[Evaluation] Zoom delete failed: {}", e.getMessage());
            }
        }
        eval.setStatus("CANCELLED");
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "CANCEL", actor.getId());
        return saved;
    }

    // ── Intern commands ───────────────────────────────────────────────────

    @Transactional
    public InternEvaluation acknowledge(UUID evalId, String response, User caller) {
        InternEvaluation eval = mustGet(evalId);
        if (!caller.getId().equals(eval.getInternId())) {
            throw new ForbiddenException("Not your evaluation");
        }
        if (!"PUBLISHED".equals(eval.getStatus()) && !"AMENDED".equals(eval.getStatus())) {
            throw new ConflictException("Can only acknowledge PUBLISHED / AMENDED");
        }
        if (eval.getAmendmentReason() != null && eval.getAmendedAt() == null) {
            // Evaluator is mid-amendment (reason set, republish not yet done).
            throw new ConflictException("Evaluator is revising; check back shortly");
        }
        if (response != null && response.length() > 2000) {
            throw new BadRequestException("internResponse must be ≤ 2000 characters");
        }
        eval.setInternAcknowledgedAt(Instant.now());
        if (response != null && !response.isBlank()) {
            eval.setInternResponse(response.trim());
        }
        // Status stays PUBLISHED/AMENDED; acknowledged_at signals the
        // intern action. Some clients prefer a dedicated ACKNOWLEDGED status
        // for filtering, so flip when coming from PUBLISHED to make /mine
        // queries simpler.
        if ("PUBLISHED".equals(eval.getStatus())) {
            eval.setStatus("ACKNOWLEDGED");
        }
        InternEvaluation saved = evalRepository.save(eval);
        writeAudit(saved, "ACKNOWLEDGE", caller.getId());
        return saved;
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<InternEvaluation> listForIntern(UUID internId) {
        // INTERN sees PUBLISHED / ACKNOWLEDGED / AMENDED only.
        return evalRepository.findByInternIdAndStatusInOrderByCreatedAtDesc(
                internId, List.of("PUBLISHED", "ACKNOWLEDGED", "AMENDED"));
    }

    @Transactional(readOnly = true)
    public List<InternEvaluation> listUpcomingForIntern(UUID internId) {
        return evalRepository.findByInternIdAndStatusInOrderByCreatedAtDesc(
                internId, List.of("SCHEDULED"));
    }

    @Transactional(readOnly = true)
    public List<InternEvaluation> listForEvaluator(UUID evaluatorId) {
        return evalRepository.findByEvaluatorIdOrderByCreatedAtDesc(evaluatorId);
    }

    @Transactional(readOnly = true)
    public InternEvaluation getOne(UUID id) {
        return mustGet(id);
    }

    @Transactional(readOnly = true)
    public boolean internHasPublishedEvaluation(UUID internId) {
        return evalRepository.existsByInternIdAndStatusIn(internId,
                List.of("PUBLISHED", "ACKNOWLEDGED", "AMENDED"));
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private InternEvaluation mustGet(UUID id) {
        return evalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + id));
    }

    private InternLifecycle mustGetLifecycle(UUID id) {
        return lifecycleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + id));
    }

    private void ensureEvaluatorScope(InternLifecycle lc, User actor) {
        if (actor.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (lc.getEvaluatorId() != null && lc.getEvaluatorId().equals(actor.getId())) return;
        throw new ForbiddenException("Caller is not the assigned evaluator for this lifecycle");
    }

    private void ensureLifecycleActive(InternLifecycle lc) {
        if (!"ACTIVE".equals(lc.getActiveStatus())) {
            throw new ConflictException(
                    "Lifecycle is not ACTIVE (current: " + lc.getActiveStatus() + ")");
        }
    }

    private void validatePublishGate(InternEvaluation eval) {
        if (eval.getOverallScore() == null
                || eval.getOverallScore() < 1 || eval.getOverallScore() > 10) {
            throw new BadRequestException("overallScore is required and must be 1-10");
        }
        requireMinChars("strengthsNarrative", eval.getStrengthsNarrative(), 50);
        requireMinChars("areasForImprovementNarrative",
                eval.getAreasForImprovementNarrative(), 50);
        requireMinChars("improvementPlan", eval.getImprovementPlan(), 50);
    }

    private static void requireMinChars(String field, String value, int min) {
        if (value == null || value.trim().length() < min) {
            throw new BadRequestException(field + " must be at least " + min + " characters");
        }
    }

    private static void applyScores(InternEvaluation eval, Map<String, Object> body) {
        applyScore(body, "overallScore", eval::setOverallScore);
        applyScore(body, "technicalSkillsScore", eval::setTechnicalSkillsScore);
        applyScore(body, "communicationScore", eval::setCommunicationScore);
        applyScore(body, "professionalismScore", eval::setProfessionalismScore);
        applyScore(body, "learningApplicationScore", eval::setLearningApplicationScore);
    }

    private static void applyScore(Map<String, Object> body, String key,
                                    java.util.function.Consumer<Integer> sink) {
        Object v = body.get(key);
        if (v == null) return;
        int n;
        try {
            n = v instanceof Number num ? num.intValue() : Integer.parseInt(v.toString());
        } catch (NumberFormatException ex) {
            throw new BadRequestException(key + " must be an integer 1-10");
        }
        if (n < 1 || n > 10) {
            throw new BadRequestException(key + " must be 1-10");
        }
        sink.accept(n);
    }

    private static void applyText(InternEvaluation eval, Map<String, Object> body,
                                   String key, java.util.function.Consumer<String> sink) {
        Object v = body.get(key);
        if (v == null) return;
        sink.accept(v.toString().trim());
    }

    private Map<String, Object> toSnapshot(InternEvaluation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("version", e.getVersion());
        m.put("status", e.getStatus());
        m.put("overallScore", e.getOverallScore());
        m.put("technicalSkillsScore", e.getTechnicalSkillsScore());
        m.put("communicationScore", e.getCommunicationScore());
        m.put("professionalismScore", e.getProfessionalismScore());
        m.put("learningApplicationScore", e.getLearningApplicationScore());
        m.put("strengthsNarrative", e.getStrengthsNarrative());
        m.put("areasForImprovementNarrative", e.getAreasForImprovementNarrative());
        m.put("improvementPlan", e.getImprovementPlan());
        m.put("internResponse", e.getInternResponse());
        m.put("publishedAt", e.getPublishedAt());
        m.put("amendedAt", e.getAmendedAt());
        return m;
    }

    private void writeAudit(InternEvaluation e, String action, UUID actorId) {
        try {
            AuditLog entry = AuditLog.builder()
                    .entityType("InternEvaluation")
                    .entityId(e.getId())
                    .action(action)
                    .userId(actorId)
                    .subjectUserId(e.getInternId())
                    .afterJson(objectMapper.writeValueAsString(Map.of(
                            "status", e.getStatus(),
                            "version", e.getVersion(),
                            "type", e.getEvaluationType())))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception ex) {
            log.warn("[Evaluation] audit write failed: {}", ex.getMessage());
        }
    }
}
