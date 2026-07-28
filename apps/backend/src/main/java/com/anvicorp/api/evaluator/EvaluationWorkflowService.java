package com.anvicorp.api.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.EvaluationAmendment;
import com.anvicorp.api.entity.ExitRecord;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.EvaluationAmendmentRepository;
import com.anvicorp.api.repository.ExitRecordRepository;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Evaluator Phase 2 — core monthly evaluation lifecycle. */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationWorkflowService {

    private static final Set<String> RECOMMENDATIONS = Set.of(
            "EXCELLENT", "GOOD", "SATISFACTORY",
            "NEEDS_IMPROVEMENT", "UNSATISFACTORY",
            // Phase 4 — only meaningful for FINAL evaluations, accepted on the
            // wire universally; the Compose UI gates exposure to FINAL only.
            "REHIRE_ELIGIBLE");

    private final InternEvaluationRepository evalRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final UserRepository userRepo;
    private final ProjectRepository projectRepository;
    private final EvaluationAmendmentRepository amendmentRepo;
    private final AuditLogRepository auditRepo;
    private final ExitRecordRepository exitRecordRepo;
    private final MeetingProvider meetingProvider;
    private final EvaluationNotificationFanout fanout;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final EvaluatorScopeGuard evaluatorScopeGuard;

    // ── 1. Schedule ───────────────────────────────────────────────────────

    @Transactional
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail schedule(
            EvaluationWorkflowDtos.ScheduleRequest req, User caller) {
        if (req == null || req.internLifecycleId() == null) {
            throw new BadRequestException("internLifecycleId is required");
        }
        if (req.scheduledFor() == null
                || req.scheduledFor().isBefore(Instant.now().plus(1, ChronoUnit.HOURS))) {
            throw new BadRequestException(
                    "scheduledFor must be at least 1 hour in the future");
        }
        int duration = req.durationMinutes() != null ? req.durationMinutes() : 45;
        if (duration < 15 || duration > 180) {
            throw new BadRequestException("durationMinutes must be 15-180");
        }
        InternLifecycle lc = lifecycleRepo.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + req.internLifecycleId()));
        requireOwnership(lc, caller, "schedule");
        if (!"ACTIVE".equals(lc.getActiveStatus())) {
            throw new ConflictException(
                    "Intern is " + lc.getActiveStatus() + "; only ACTIVE evaluees "
                            + "can be scheduled");
        }

        YearMonth month = YearMonth.now();
        InternEvaluation ev = InternEvaluation.builder()
                .internLifecycleId(lc.getId())
                .internId(lc.getUserId())
                .evaluatorId(caller.getId())
                .evaluationType("MONTHLY")
                .periodStart(month.atDay(1))
                .periodEnd(month.atEndOfMonth())
                .scheduledFor(req.scheduledFor())
                .durationMinutes(duration)
                .timezone(req.timezone() != null && !req.timezone().isBlank()
                        ? req.timezone() : "UTC")
                .status("SCHEDULED")
                .version(1)
                .build();

        // Best-effort Zoom — failure leaves the evaluation row in SCHEDULED
        // status without a join URL; Evaluator can paste a manual link later.
        if (meetingProvider.isReady()) {
            try {
                String topic = req.topic() != null && !req.topic().isBlank()
                        ? req.topic()
                        : "Monthly Evaluation — " + month.toString();
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(
                                caller.getZoomEmail() != null && !caller.getZoomEmail().isBlank()
                                        ? caller.getZoomEmail() : "me",
                                topic, req.scheduledFor(), duration,
                                ev.getTimezone(), req.agenda()));
                ev.setZoomMeetingId(z.providerMeetingId());
                ev.setZoomJoinUrl(z.joinUrl());
                ev.setZoomStartUrl(z.startUrl());
                ev.setZoomPassword(z.password());
            } catch (Exception e) {
                log.warn("[EvaluationWorkflow] Zoom create failed (degraded): {}",
                        e.getMessage());
            }
        }

        InternEvaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "EVALUATION_SCHEDULED",
                saved.getId(), null, Map.of(
                        "scheduledFor", saved.getScheduledFor().toString(),
                        "durationMinutes", duration,
                        "month", month.toString()));
        fanout.evaluationScheduled(saved, lc, caller);
        return toEvaluatorDetail(saved, lc);
    }

    // ── 1b. Schedule Final (Phase 4) ──────────────────────────────────────

    /**
     * Phase 4 — schedule a FINAL evaluation. Distinct from monthly schedule
     * because: (1) period_start = lifecycle.started_at (full engagement),
     * (2) gated by ExitRecord existence (the wrap-up signal) or SUPER_ADMIN,
     * (3) blocks if any prior FINAL is open for the lifecycle (one Final per
     * engagement; amend the existing row instead of creating a second).
     */
    @Transactional
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail scheduleFinal(
            EvaluationWorkflowDtos.ScheduleFinalRequest req, User caller) {
        if (req == null || req.internLifecycleId() == null) {
            throw new BadRequestException("internLifecycleId is required");
        }
        if (req.scheduledFor() == null
                || req.scheduledFor().isBefore(Instant.now().plus(1, ChronoUnit.HOURS))) {
            throw new BadRequestException(
                    "scheduledFor must be at least 1 hour in the future");
        }
        int duration = req.durationMinutes() != null ? req.durationMinutes() : 60;
        if (duration < 15 || duration > 180) {
            throw new BadRequestException("durationMinutes must be 15-180");
        }
        InternLifecycle lc = lifecycleRepo.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + req.internLifecycleId()));
        requireOwnership(lc, caller, "scheduleFinal");

        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        // Wrap-up signal: ERM has opened the exit flow. SUPER_ADMIN bypasses
        // the guard for off-cycle reviews / one-offs.
        boolean hasExit = exitRecordRepo.existsByInternLifecycleId(lc.getId());
        if (!superAdmin && !hasExit) {
            throw new ConflictException(
                    "FINAL evaluation requires an ExitRecord on the lifecycle "
                            + "(ERM must initiate exit first).");
        }

        // One Final per engagement — block if any open FINAL row exists.
        Long openFinalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM intern_evaluations "
                        + " WHERE intern_lifecycle_id = ? "
                        + "   AND evaluation_type = 'FINAL' "
                        + "   AND status IN ('DRAFT','SCHEDULED','IN_PROGRESS','PUBLISHED','ACKNOWLEDGED','AMENDED')",
                Long.class, lc.getId());
        if (openFinalCount != null && openFinalCount > 0) {
            throw new ConflictException(
                    "A FINAL evaluation already exists for this intern — "
                            + "open or amend the existing row instead.");
        }

        // period_start = engagement start; period_end = the review meeting date.
        LocalDate periodStart = lc.getStartedAt() != null
                ? lc.getStartedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                : LocalDate.now().minusMonths(6);
        LocalDate periodEnd = req.scheduledFor()
                .atZone(java.time.ZoneOffset.UTC).toLocalDate();

        InternEvaluation ev = InternEvaluation.builder()
                .internLifecycleId(lc.getId())
                .internId(lc.getUserId())
                .evaluatorId(caller.getId())
                .evaluationType("FINAL")
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .scheduledFor(req.scheduledFor())
                .durationMinutes(duration)
                .timezone(req.timezone() != null && !req.timezone().isBlank()
                        ? req.timezone() : "UTC")
                .status("SCHEDULED")
                .version(1)
                .build();

        if (meetingProvider.isReady()) {
            try {
                String topic = req.topic() != null && !req.topic().isBlank()
                        ? req.topic()
                        : "Final Evaluation — End of Internship";
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(
                                caller.getZoomEmail() != null && !caller.getZoomEmail().isBlank()
                                        ? caller.getZoomEmail() : "me",
                                topic, req.scheduledFor(), duration,
                                ev.getTimezone(), req.agenda()));
                ev.setZoomMeetingId(z.providerMeetingId());
                ev.setZoomJoinUrl(z.joinUrl());
                ev.setZoomStartUrl(z.startUrl());
                ev.setZoomPassword(z.password());
            } catch (Exception e) {
                log.warn("[EvaluationWorkflow] Zoom create failed for FINAL (degraded): {}",
                        e.getMessage());
            }
        }

        InternEvaluation saved = evalRepo.save(ev);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("scheduledFor", saved.getScheduledFor().toString());
        after.put("durationMinutes", duration);
        after.put("evaluationType", "FINAL");
        after.put("periodStart", periodStart.toString());
        after.put("periodEnd", periodEnd.toString());
        writeAudit(caller, lc.getUserId(), "FINAL_EVALUATION_SCHEDULED",
                saved.getId(), null, after);
        fanout.evaluationScheduled(saved, lc, caller);
        return toEvaluatorDetail(saved, lc);
    }

    // ── 2. Start (SCHEDULED → IN_PROGRESS) ────────────────────────────────

    @Transactional
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail start(
            UUID evaluationId, User caller) {
        InternEvaluation ev = mustGet(evaluationId);
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        requireEvaluatorIs(ev, caller, "start");
        if (!"SCHEDULED".equals(ev.getStatus())) {
            throw new ConflictException(
                    "Can only start from SCHEDULED (current: " + ev.getStatus() + ")");
        }
        ev.setStatus("IN_PROGRESS");
        InternEvaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "EVALUATION_STARTED",
                saved.getId(),
                Map.of("status", "SCHEDULED"),
                Map.of("status", "IN_PROGRESS"));
        return toEvaluatorDetail(saved, lc);
    }

    // ── 3. Save draft ─────────────────────────────────────────────────────

    @Transactional
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail saveDraft(
            UUID evaluationId,
            EvaluationWorkflowDtos.SaveDraftRequest req, User caller) {
        InternEvaluation ev = mustGet(evaluationId);
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        requireEvaluatorIs(ev, caller, "saveDraft");
        if (!Set.of("IN_PROGRESS", "SCHEDULED").contains(ev.getStatus())) {
            throw new ConflictException(
                    "Drafts allowed only in SCHEDULED / IN_PROGRESS (current: "
                            + ev.getStatus() + ")");
        }
        if (req != null) {
            applyRubric(ev, req.technicalSkillsScore(), req.communicationScore(),
                    req.professionalismScore(), req.learningApplicationScore(),
                    /*requireAll=*/ false);
            if (req.strengths() != null) ev.setStrengthsNarrative(trim(req.strengths(), 5000));
            if (req.areasForImprovement() != null) {
                ev.setAreasForImprovementNarrative(trim(req.areasForImprovement(), 5000));
            }
            if (req.comments() != null) ev.setImprovementPlan(trim(req.comments(), 5000));
            if (req.recommendation() != null && !req.recommendation().isBlank()) {
                if (!RECOMMENDATIONS.contains(req.recommendation())) {
                    throw new BadRequestException(
                            "recommendation must be one of " + RECOMMENDATIONS);
                }
                ev.setRecommendation(req.recommendation());
            }
            if (req.internalNotes() != null) {
                ev.setInternalNotes(trim(req.internalNotes(), 5000));
            }
        }
        // Implicit transition: typing in the form auto-advances scheduled → in_progress
        if ("SCHEDULED".equals(ev.getStatus())) ev.setStatus("IN_PROGRESS");
        return toEvaluatorDetail(evalRepo.save(ev), lc);
    }

    // ── 4. Publish ────────────────────────────────────────────────────────

    @Transactional
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail publish(
            UUID evaluationId, User caller) {
        InternEvaluation ev = mustGet(evaluationId);
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        requireEvaluatorIs(ev, caller, "publish");
        if (!"IN_PROGRESS".equals(ev.getStatus())) {
            throw new ConflictException(
                    "Publish requires IN_PROGRESS (current: " + ev.getStatus() + ")");
        }
        // Require all 4 rubric scores + recommendation + ≥50 chars total feedback.
        if (ev.getTechnicalSkillsScore() == null
                || ev.getCommunicationScore() == null
                || ev.getProfessionalismScore() == null
                || ev.getLearningApplicationScore() == null) {
            throw new BadRequestException(
                    "All 4 rubric scores are required to publish");
        }
        if (ev.getRecommendation() == null
                || !RECOMMENDATIONS.contains(ev.getRecommendation())) {
            throw new BadRequestException("recommendation is required to publish");
        }
        int feedbackChars = textLen(ev.getStrengthsNarrative())
                + textLen(ev.getAreasForImprovementNarrative())
                + textLen(ev.getImprovementPlan());
        if (feedbackChars < 50) {
            throw new BadRequestException(
                    "Free-text feedback must total at least 50 characters across "
                            + "strengths / improvements / comments");
        }
        // Compute overall as the integer average of the 4 rubric scores.
        int overall = (int) Math.round((
                ev.getTechnicalSkillsScore()
                        + ev.getCommunicationScore()
                        + ev.getProfessionalismScore()
                        + ev.getLearningApplicationScore()) / 4.0);
        ev.setOverallScore(overall);
        ev.setStatus("PUBLISHED");
        Instant now = Instant.now();
        ev.setPublishedAt(now);
        InternEvaluation saved = evalRepo.save(ev);
        // Phase 4 — link Final publish to the open ExitRecord so the ERM
        // exit checklist surfaces "Final evaluation: complete". Best-effort:
        // missing exit record is fine (SUPER_ADMIN paths can publish without).
        if ("FINAL".equals(saved.getEvaluationType())) {
            try {
                exitRecordRepo.findByInternLifecycleId(lc.getId()).ifPresent(er -> {
                    if (er.getFinalEvaluationId() == null) {
                        er.setFinalEvaluationId(saved.getId());
                        exitRecordRepo.save(er);
                    }
                });
            } catch (Exception e) {
                log.warn("[EvaluationWorkflow] ExitRecord link on FINAL publish failed (degraded): {}",
                        e.getMessage());
            }
        }
        writeAudit(caller, lc.getUserId(), "EVALUATION_PUBLISHED",
                saved.getId(),
                Map.of("status", "IN_PROGRESS"),
                buildPublishedAfterMap(saved));
        fanout.evaluationPublished(saved, lc, caller);
        return toEvaluatorDetail(saved, lc);
    }

    // ── 5. Acknowledge (intern-side) ──────────────────────────────────────

    @Transactional
    public EvaluationWorkflowDtos.InternEvaluationView acknowledge(
            UUID evaluationId,
            EvaluationWorkflowDtos.AcknowledgeRequest req, User caller) {
        InternEvaluation ev = mustGet(evaluationId);
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        if (!caller.getId().equals(lc.getUserId())) {
            throw new ForbiddenException(
                    "Only the evaluated intern may acknowledge this evaluation");
        }
        if (!Set.of("PUBLISHED", "AMENDED").contains(ev.getStatus())) {
            throw new ConflictException(
                    "Acknowledge requires PUBLISHED or AMENDED "
                            + "(current: " + ev.getStatus() + ")");
        }
        Instant now = Instant.now();
        ev.setStatus("ACKNOWLEDGED");
        ev.setInternAcknowledgedAt(now);
        if (req != null && req.internResponse() != null
                && !req.internResponse().isBlank()) {
            ev.setInternResponse(trim(req.internResponse(), 2000));
        }
        InternEvaluation saved = evalRepo.save(ev);
        writeAudit(caller, caller.getId(), "EVALUATION_ACKNOWLEDGED",
                saved.getId(),
                Map.of("status", "PUBLISHED"),
                buildAckAfterMap(saved));
        fanout.evaluationAcknowledged(saved, lc, caller);
        return toInternView(saved, lookupName(saved.getEvaluatorId()));
    }

    // ── 6. Amend ──────────────────────────────────────────────────────────

    @Transactional
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail amend(
            UUID evaluationId,
            EvaluationWorkflowDtos.AmendRequest req, User caller) {
        if (req == null || req.amendmentReason() == null
                || req.amendmentReason().trim().length() < 30) {
            throw new BadRequestException(
                    "amendmentReason must be at least 30 characters");
        }
        InternEvaluation ev = mustGet(evaluationId);
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        if (!Set.of("PUBLISHED", "ACKNOWLEDGED", "AMENDED").contains(ev.getStatus())) {
            throw new ConflictException(
                    "Amend requires PUBLISHED / ACKNOWLEDGED / AMENDED "
                            + "(current: " + ev.getStatus() + ")");
        }
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (!superAdmin && !caller.getId().equals(ev.getEvaluatorId())) {
            throw new ForbiddenException(
                    "Only the publishing Evaluator can amend this evaluation");
        }
        int previousVersion = ev.getVersion() != null ? ev.getVersion() : 1;

        // Snapshot pre-amendment state into evaluation_amendments.
        try {
            String snapshot = objectMapper.writeValueAsString(snapshotMap(ev));
            EvaluationAmendment am = EvaluationAmendment.builder()
                    .evaluationId(ev.getId())
                    .amendedById(caller.getId())
                    .amendmentReason(req.amendmentReason().trim())
                    .previousVersion(previousVersion)
                    .newVersion(previousVersion + 1)
                    .snapshotJson(snapshot)
                    .build();
            amendmentRepo.save(am);
        } catch (Exception e) {
            log.warn("[EvaluationWorkflow] amendment snapshot persist failed: {}",
                    e.getMessage());
        }

        // Apply updates if provided; otherwise keep existing values.
        if (req.technicalSkillsScore() != null
                || req.communicationScore() != null
                || req.professionalismScore() != null
                || req.learningApplicationScore() != null) {
            applyRubric(ev, req.technicalSkillsScore(), req.communicationScore(),
                    req.professionalismScore(), req.learningApplicationScore(),
                    /*requireAll=*/ false);
        }
        if (req.strengths() != null) ev.setStrengthsNarrative(trim(req.strengths(), 5000));
        if (req.areasForImprovement() != null) {
            ev.setAreasForImprovementNarrative(trim(req.areasForImprovement(), 5000));
        }
        if (req.comments() != null) ev.setImprovementPlan(trim(req.comments(), 5000));
        if (req.recommendation() != null && !req.recommendation().isBlank()) {
            if (!RECOMMENDATIONS.contains(req.recommendation())) {
                throw new BadRequestException(
                        "recommendation must be one of " + RECOMMENDATIONS);
            }
            ev.setRecommendation(req.recommendation());
        }
        // Recompute overall after edits.
        if (ev.getTechnicalSkillsScore() != null
                && ev.getCommunicationScore() != null
                && ev.getProfessionalismScore() != null
                && ev.getLearningApplicationScore() != null) {
            ev.setOverallScore((int) Math.round((
                    ev.getTechnicalSkillsScore()
                            + ev.getCommunicationScore()
                            + ev.getProfessionalismScore()
                            + ev.getLearningApplicationScore()) / 4.0));
        }

        Instant now = Instant.now();
        ev.setVersion(previousVersion + 1);
        ev.setAmendedAt(now);
        ev.setAmendmentReason(req.amendmentReason().trim());
        ev.setStatus("AMENDED");
        ev.setInternAcknowledgedAt(null);
        ev.setInternResponse(null);

        InternEvaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "EVALUATION_AMENDED",
                saved.getId(),
                Map.of("version", previousVersion),
                buildAmendmentAfterMap(saved, req.amendmentReason()));
        fanout.evaluationAmended(saved, lc, caller, req.amendmentReason());
        return toEvaluatorDetail(saved, lc);
    }

    // ── Read endpoints ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EvaluationWorkflowDtos.EvaluatorEvaluationDetail getEvaluatorDetail(
            UUID evaluationId, User caller) {
        InternEvaluation ev = mustGet(evaluationId);
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (!superAdmin
                && !caller.getId().equals(ev.getEvaluatorId())
                && !caller.getId().equals(lc.getEvaluatorId())) {
            throw new ForbiddenException(
                    "Not the assigned Evaluator for this evaluation");
        }
        return toEvaluatorDetail(ev, lc);
    }

    @Transactional(readOnly = true)
    public EvaluationWorkflowDtos.InternEvaluationView getInternView(
            UUID evaluationId, User caller) {
        InternEvaluation ev = mustGet(evaluationId);
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        if (!caller.getId().equals(lc.getUserId())) {
            throw new ForbiddenException(
                    "Only the evaluated intern can view this evaluation");
        }
        if ("DRAFT".equals(ev.getStatus()) || "SCHEDULED".equals(ev.getStatus())
                || "IN_PROGRESS".equals(ev.getStatus())) {
            throw new ConflictException(
                    "Evaluation is not yet published");
        }
        return toInternView(ev, lookupName(ev.getEvaluatorId()));
    }

    @Transactional(readOnly = true)
    public List<EvaluationWorkflowDtos.InternEvaluationRow> listForIntern(User caller) {
        Optional<InternLifecycle> lcOpt = lifecycleRepo.findByUserId(caller.getId());
        if (lcOpt.isEmpty()) return List.of();
        UUID lifecycleId = lcOpt.get().getId();
        return jdbc.query(
                "SELECT ev.id, ev.evaluation_type, ev.status, ev.version, "
                        + "ev.published_at, ev.intern_acknowledged_at, "
                        + "ev.overall_score, ev.recommendation, "
                        + "u.full_name AS evaluator_name "
                        + "FROM intern_evaluations ev "
                        + "LEFT JOIN users u ON u.id = ev.evaluator_id "
                        + "WHERE ev.intern_lifecycle_id = ? "
                        + "  AND ev.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                        + "ORDER BY ev.published_at DESC NULLS LAST",
                (rs, n) -> {
                    Object scoreObj = rs.getObject("overall_score");
                    Integer score = scoreObj != null
                            ? ((Number) scoreObj).intValue() : null;
                    return new EvaluationWorkflowDtos.InternEvaluationRow(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("evaluator_name"),
                            rs.getString("evaluation_type"),
                            rs.getString("status"),
                            rs.getInt("version"),
                            rs.getTimestamp("published_at") != null
                                    ? rs.getTimestamp("published_at").toInstant() : null,
                            rs.getTimestamp("intern_acknowledged_at") != null
                                    ? rs.getTimestamp("intern_acknowledged_at").toInstant() : null,
                            score,
                            rs.getString("recommendation"));
                },
                lifecycleId);
    }

    @Transactional(readOnly = true)
    public Optional<EvaluationWorkflowDtos.InternEvaluationRow> firstUnacked(User caller) {
        return listForIntern(caller).stream()
                .filter(r -> r.internAcknowledgedAt() == null)
                .findFirst();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private InternEvaluation mustGet(UUID id) {
        return evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "Evaluation not found: " + id));
    }

    private void requireOwnership(InternLifecycle lc, User caller, String action) {
        // Delegate to the shared EvaluatorScopeGuard so schedule /
        // scheduleFinal inherit the single-evaluator null-fallback the
        // trainer side already uses. The `action` label is retained on
        // call-sites for log context; the guard's message is
        // role-agnostic + intent-clear.
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);
    }

    private void requireEvaluatorIs(InternEvaluation ev, User caller, String action) {
        // Row-level check now derives from the SAME predicate as the
        // lifecycle-level guard so the org evaluator on a null-link
        // intern (or one whose evaluation row was stamped under a
        // prior default-evaluator account) is never locked out. Load
        // the lifecycle for the row and delegate.
        InternLifecycle lc = ev.getInternLifecycleId() != null
                ? lifecycleRepo.findById(ev.getInternLifecycleId()).orElse(null)
                : null;
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);
    }

    private void applyRubric(InternEvaluation ev,
                              Integer technical, Integer communication,
                              Integer professionalism, Integer learning,
                              boolean requireAll) {
        if (technical != null) {
            validateScore("technicalSkillsScore", technical);
            ev.setTechnicalSkillsScore(technical);
        } else if (requireAll) {
            throw new BadRequestException("technicalSkillsScore is required");
        }
        if (communication != null) {
            validateScore("communicationScore", communication);
            ev.setCommunicationScore(communication);
        } else if (requireAll) {
            throw new BadRequestException("communicationScore is required");
        }
        if (professionalism != null) {
            validateScore("professionalismScore", professionalism);
            ev.setProfessionalismScore(professionalism);
        } else if (requireAll) {
            throw new BadRequestException("professionalismScore is required");
        }
        if (learning != null) {
            validateScore("learningApplicationScore", learning);
            ev.setLearningApplicationScore(learning);
        } else if (requireAll) {
            throw new BadRequestException("learningApplicationScore is required");
        }
    }

    private static void validateScore(String field, int score) {
        if (score < 1 || score > 5) {
            throw new BadRequestException(field + " must be between 1 and 5");
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static int textLen(String s) {
        return s == null ? 0 : s.trim().length();
    }

    private String lookupName(UUID userId) {
        if (userId == null) return null;
        try {
            return userRepo.findById(userId).map(User::getFullName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> snapshotMap(InternEvaluation ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", ev.getVersion());
        m.put("status", ev.getStatus());
        m.put("technicalSkillsScore", ev.getTechnicalSkillsScore());
        m.put("communicationScore", ev.getCommunicationScore());
        m.put("professionalismScore", ev.getProfessionalismScore());
        m.put("learningApplicationScore", ev.getLearningApplicationScore());
        m.put("overallScore", ev.getOverallScore());
        m.put("recommendation", ev.getRecommendation());
        m.put("strengthsNarrative", ev.getStrengthsNarrative());
        m.put("areasForImprovementNarrative", ev.getAreasForImprovementNarrative());
        m.put("improvementPlan", ev.getImprovementPlan());
        m.put("publishedAt", ev.getPublishedAt() != null
                ? ev.getPublishedAt().toString() : null);
        m.put("internAcknowledgedAt", ev.getInternAcknowledgedAt() != null
                ? ev.getInternAcknowledgedAt().toString() : null);
        m.put("internResponse", ev.getInternResponse());
        return m;
    }

    private Map<String, Object> buildPublishedAfterMap(InternEvaluation ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "PUBLISHED");
        m.put("overallScore", ev.getOverallScore());
        m.put("recommendation", ev.getRecommendation());
        m.put("publishedAt", ev.getPublishedAt() != null
                ? ev.getPublishedAt().toString() : null);
        return m;
    }

    private Map<String, Object> buildAckAfterMap(InternEvaluation ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ACKNOWLEDGED");
        m.put("internAcknowledgedAt", ev.getInternAcknowledgedAt() != null
                ? ev.getInternAcknowledgedAt().toString() : null);
        m.put("internResponseLen", textLen(ev.getInternResponse()));
        return m;
    }

    private Map<String, Object> buildAmendmentAfterMap(InternEvaluation ev, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "AMENDED");
        m.put("version", ev.getVersion());
        m.put("amendmentReason", reason);
        m.put("overallScore", ev.getOverallScore());
        m.put("recommendation", ev.getRecommendation());
        return m;
    }

    private EvaluationWorkflowDtos.EvaluatorEvaluationDetail toEvaluatorDetail(
            InternEvaluation ev, InternLifecycle lc) {
        User intern = userRepo.findById(lc.getUserId()).orElse(null);
        String technology = null;
        try {
            technology = jdbc.queryForObject(
                    "SELECT jp.title FROM applications a "
                            + " JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + "WHERE a.candidate_id IN ( "
                            + "    SELECT id FROM candidates c WHERE c.user_id = ?) "
                            + "ORDER BY a.applied_at DESC LIMIT 1",
                    String.class, lc.getUserId());
        } catch (Exception ignored) {}
        Double avg = averageOf(ev);
        EvaluationWorkflowDtos.LinkedProjectSummary linkedProject = null;
        if (ev.getLinkedProjectId() != null) {
            Project p = projectRepository.findById(ev.getLinkedProjectId()).orElse(null);
            if (p != null) {
                linkedProject = new EvaluationWorkflowDtos.LinkedProjectSummary(
                        p.getId(),
                        p.getTitle() != null ? p.getTitle() : p.getName(),
                        p.getTechStack(),
                        p.getStatus() != null ? p.getStatus().name() : null,
                        p.getProjectNumber() != null ? p.getProjectNumber().intValue() : null,
                        p.getMonthYear(),
                        p.getDueDate(),
                        p.getSubmittedAt(),
                        p.getCompletedAt());
            }
        }
        return new EvaluationWorkflowDtos.EvaluatorEvaluationDetail(
                ev.getId(), lc.getId(), lc.getUserId(),
                intern != null ? intern.getFullName() : null,
                intern != null ? intern.getEmail() : null,
                lc.getEmployeeId(),
                technology,
                ev.getEvaluationType(), ev.getStatus(),
                ev.getVersion() != null ? ev.getVersion() : 1,
                ev.getPeriodStart(), ev.getPeriodEnd(),
                ev.getScheduledFor(), ev.getDurationMinutes(), ev.getTimezone(),
                ev.getZoomJoinUrl(), ev.getZoomStartUrl(), ev.getZoomMeetingId(),
                ev.getTechnicalSkillsScore(), ev.getCommunicationScore(),
                ev.getProfessionalismScore(), ev.getLearningApplicationScore(),
                avg,
                ev.getStrengthsNarrative(),
                ev.getAreasForImprovementNarrative(),
                ev.getImprovementPlan(),
                ev.getRecommendation(),
                ev.getInternalNotes(),
                ev.getPublishedAt(), ev.getInternAcknowledgedAt(),
                ev.getInternResponse(),
                ev.getAmendedAt(), ev.getAmendmentReason(),
                loadAmendmentHistory(ev.getId()),
                ev.getRecordingDocumentId(),
                ev.getLinkedProjectId(),
                linkedProject);
    }

    private List<EvaluationWorkflowDtos.AmendmentEntry> loadAmendmentHistory(UUID evalId) {
        try {
            return jdbc.query(
                    "SELECT a.id, a.amended_by_id, a.amendment_reason, "
                            + "a.previous_version, a.new_version, a.amended_at, "
                            + "u.full_name AS amended_by_name "
                            + "FROM evaluation_amendments a "
                            + "LEFT JOIN users u ON u.id = a.amended_by_id "
                            + "WHERE a.evaluation_id = ? "
                            + "ORDER BY a.amended_at DESC",
                    (rs, n) -> new EvaluationWorkflowDtos.AmendmentEntry(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("amended_by_id")),
                            rs.getString("amended_by_name"),
                            rs.getString("amendment_reason"),
                            rs.getInt("previous_version"),
                            rs.getInt("new_version"),
                            rs.getTimestamp("amended_at").toInstant()),
                    evalId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private EvaluationWorkflowDtos.InternEvaluationView toInternView(
            InternEvaluation ev, String evaluatorName) {
        Double avg = averageOf(ev);
        return new EvaluationWorkflowDtos.InternEvaluationView(
                ev.getId(), evaluatorName,
                ev.getEvaluationType(), ev.getStatus(),
                ev.getVersion() != null ? ev.getVersion() : 1,
                ev.getPeriodStart(), ev.getPeriodEnd(),
                ev.getScheduledFor(),
                ev.getZoomJoinUrl(),
                ev.getTechnicalSkillsScore(), ev.getCommunicationScore(),
                ev.getProfessionalismScore(), ev.getLearningApplicationScore(),
                avg,
                ev.getStrengthsNarrative(),
                ev.getAreasForImprovementNarrative(),
                ev.getImprovementPlan(),
                ev.getRecommendation(),
                ev.getPublishedAt(), ev.getInternAcknowledgedAt(),
                ev.getInternResponse(),
                ev.getAmendedAt());
    }

    private static Double averageOf(InternEvaluation ev) {
        int n = 0; double sum = 0;
        if (ev.getTechnicalSkillsScore() != null) { sum += ev.getTechnicalSkillsScore(); n++; }
        if (ev.getCommunicationScore() != null) { sum += ev.getCommunicationScore(); n++; }
        if (ev.getProfessionalismScore() != null) { sum += ev.getProfessionalismScore(); n++; }
        if (ev.getLearningApplicationScore() != null) { sum += ev.getLearningApplicationScore(); n++; }
        return n == 0 ? null : sum / n;
    }

    private void writeAudit(User caller, UUID subjectUserId, String action,
                             UUID entityId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(caller != null ? caller.getId() : null)
                    .subjectUserId(subjectUserId)
                    .entityType("InternEvaluation")
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null
                            ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null
                            ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditRepo.save(row);
        } catch (Exception e) {
            log.warn("[EvaluationWorkflow] audit write failed: {}", e.getMessage());
        }
    }
}
