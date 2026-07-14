package com.anvicorp.api.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.I983Evaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.I983EvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Evaluator Phase 3 — I-983 evaluation workflow for STEM OPT interns. */
@Service
@RequiredArgsConstructor
@Slf4j
public class I983EvaluationWorkflowService {

    private static final Set<String> TYPES = Set.of(
            "INITIAL_PLAN", "ANNUAL_REVIEW", "FINAL_REVIEW");
    private static final Set<String> SUBMISSION_METHODS = Set.of(
            "EMAIL_TO_DSO", "PORTAL_UPLOAD", "IN_PERSON", "MAIL");

    private final I983EvaluationRepository evalRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final UserRepository userRepo;
    private final AuditLogRepository auditRepo;
    private final EvaluationNotificationFanout fanout;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final EvaluatorScopeGuard evaluatorScopeGuard;

    // ── List (3 tabs in one response) ─────────────────────────────────────

    @Transactional(readOnly = true)
    public I983WorkflowDtos.I983ListResponse list(User caller) {
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        UUID evaluatorId = caller.getId();

        // 1) Existing I-983 evaluation rows (in-progress + completed)
        String evalSql = "SELECT ie.id, ie.intern_lifecycle_id, "
                + "u.full_name AS intern_name, il.employee_id, "
                + "ie.evaluation_type, ie.status, ie.published_at, "
                + "ie.acknowledged_at, ie.dso_submitted_to_school_at, "
                + "ip.training_start_date, ip.training_end_date "
                + "FROM i983_evaluations ie "
                + "JOIN intern_lifecycles il ON il.id = ie.intern_lifecycle_id "
                + "JOIN users u ON u.id = il.user_id "
                + "LEFT JOIN candidates c ON c.user_id = il.user_id "
                + "LEFT JOIN LATERAL ( "
                + "    SELECT training_start_date, training_end_date "
                + "      FROM i983_plans WHERE candidate_id = c.id "
                + "      ORDER BY created_at DESC LIMIT 1 "
                + ") ip ON TRUE "
                // Single-evaluator fallback — include rows where the
                // row's evaluator_id is null OR the lifecycle's
                // evaluator_id is null, so the list matches the
                // EvaluatorScopeGuard's null-fallback semantics used by
                // the detail/write paths.
                + (orgWide ? "" : "WHERE (ie.evaluator_id = ? "
                        + "OR ie.evaluator_id IS NULL "
                        + "OR il.evaluator_id IS NULL) ")
                + "ORDER BY COALESCE(ie.published_at, ie.created_at) DESC";
        List<I983WorkflowDtos.I983ListRow> evalRows = new ArrayList<>();
        try {
            evalRows = jdbc.query(evalSql,
                    orgWide ? new Object[0] : new Object[]{evaluatorId},
                    (rs, n) -> {
                        Date tsd = rs.getDate("training_start_date");
                        Date ted = rs.getDate("training_end_date");
                        return new I983WorkflowDtos.I983ListRow(
                                "EVALUATION",
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("intern_lifecycle_id")),
                                rs.getString("intern_name"),
                                rs.getString("employee_id"),
                                rs.getString("evaluation_type"),
                                rs.getString("status"),
                                null,
                                rs.getTimestamp("published_at") != null
                                        ? rs.getTimestamp("published_at").toInstant() : null,
                                rs.getTimestamp("acknowledged_at") != null
                                        ? rs.getTimestamp("acknowledged_at").toInstant() : null,
                                rs.getTimestamp("dso_submitted_to_school_at") != null
                                        ? rs.getTimestamp("dso_submitted_to_school_at").toInstant() : null,
                                tsd != null ? tsd.toLocalDate() : null,
                                ted != null ? ted.toLocalDate() : null,
                                null, null, true);
                    });
        } catch (Exception e) {
            log.warn("[I983.list] eval query failed: {}", e.getMessage());
        }

        // 2) Eligible interns (STEM OPT, no in-progress evaluation, computed due date)
        String eligibleSql = "SELECT il.id, u.full_name AS intern_name, "
                + "il.employee_id, ip.training_start_date, ip.training_end_date "
                + "FROM intern_lifecycles il "
                + "JOIN users u ON u.id = il.user_id "
                + "JOIN work_authorization_records w ON w.user_id = il.user_id "
                + "LEFT JOIN candidates c ON c.user_id = il.user_id "
                + "LEFT JOIN LATERAL ( "
                + "    SELECT training_start_date, training_end_date "
                + "      FROM i983_plans WHERE candidate_id = c.id "
                + "      ORDER BY created_at DESC LIMIT 1 "
                + ") ip ON TRUE "
                + "WHERE il.active_status = 'ACTIVE' "
                + "  AND w.work_auth_type = 'F1_STEM_OPT' "
                // Eligible-interns tab — same null fallback: a
                // null-evaluator STEM-OPT intern shouldn't be invisible
                // to the org evaluator just because the explicit link
                // was never stamped.
                + (orgWide ? "" : "  AND (il.evaluator_id = ? "
                        + "OR il.evaluator_id IS NULL) ");
        List<I983WorkflowDtos.I983ListRow> eligible = new ArrayList<>();
        try {
            eligible = jdbc.query(eligibleSql,
                    orgWide ? new Object[0] : new Object[]{evaluatorId},
                    (rs, n) -> {
                        Date tsd = rs.getDate("training_start_date");
                        Date ted = rs.getDate("training_end_date");
                        LocalDate next = computeNextDue(
                                tsd != null ? tsd.toLocalDate() : null,
                                ted != null ? ted.toLocalDate() : null);
                        Integer days = next != null
                                ? (int) ChronoUnit.DAYS.between(LocalDate.now(), next)
                                : null;
                        return new I983WorkflowDtos.I983ListRow(
                                "INTERN_ELIGIBLE",
                                null,
                                UUID.fromString(rs.getString("id")),
                                rs.getString("intern_name"),
                                rs.getString("employee_id"),
                                next != null && ted != null && next.equals(ted)
                                        ? "FINAL_REVIEW" : "ANNUAL_REVIEW",
                                null, null, null, null, null,
                                tsd != null ? tsd.toLocalDate() : null,
                                ted != null ? ted.toLocalDate() : null,
                                next, days, tsd != null);
                    });
        } catch (Exception e) {
            log.warn("[I983.list] eligible query failed: {}", e.getMessage());
        }

        // Partition rows into 3 tabs.
        List<I983WorkflowDtos.I983ListRow> dueSoon = new ArrayList<>();
        List<I983WorkflowDtos.I983ListRow> inProgress = new ArrayList<>();
        List<I983WorkflowDtos.I983ListRow> completed = new ArrayList<>();

        for (I983WorkflowDtos.I983ListRow r : evalRows) {
            if (Set.of("ACKNOWLEDGED", "AMENDED").contains(r.status())
                    || (r.status() != null && r.status().equals("PUBLISHED")
                        && r.dsoSubmittedAt() != null)) {
                completed.add(r);
            } else if (Set.of("SCHEDULED", "IN_PROGRESS", "PUBLISHED").contains(r.status())) {
                inProgress.add(r);
            } else {
                completed.add(r);
            }
        }
        // Eligible interns with no in-progress evaluation join the dueSoon tab
        // when their next-due date is past or within 60 days. Skip those
        // already represented in the eval rows.
        Set<UUID> lifecyclesWithActiveEval = new java.util.HashSet<>();
        for (I983WorkflowDtos.I983ListRow r : inProgress) {
            lifecyclesWithActiveEval.add(r.internLifecycleId());
        }
        for (I983WorkflowDtos.I983ListRow r : eligible) {
            if (lifecyclesWithActiveEval.contains(r.internLifecycleId())) continue;
            if (r.daysUntilDue() == null) {
                // No plan / no start date — still show in dueSoon so ERM/
                // Evaluator know to chase ERM Compliance.
                dueSoon.add(r);
            } else if (r.daysUntilDue() <= 60) {
                dueSoon.add(r);
            }
        }

        return new I983WorkflowDtos.I983ListResponse(dueSoon, inProgress, completed);
    }

    private LocalDate computeNextDue(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        if (start == null && end == null) return null;
        if (start != null) {
            LocalDate annual = start.plusMonths(12);
            if (today.isBefore(annual.plusMonths(1))) return annual;
        }
        if (end != null) return end;
        return null;
    }

    // ── Schedule ─────────────────────────────────────────────────────────

    @Transactional
    public I983WorkflowDtos.EvaluatorI983Detail schedule(
            I983WorkflowDtos.ScheduleI983Request req, User caller) {
        if (req == null || req.internLifecycleId() == null) {
            throw new BadRequestException("internLifecycleId is required");
        }
        if (req.evaluationType() == null
                || !TYPES.contains(req.evaluationType())) {
            throw new BadRequestException(
                    "evaluationType must be one of " + TYPES);
        }
        InternLifecycle lc = lifecycleRepo.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + req.internLifecycleId()));
        requireOwnership(lc, caller);
        if (!"ACTIVE".equals(lc.getActiveStatus())) {
            throw new ConflictException(
                    "Intern is " + lc.getActiveStatus() + "; only ACTIVE evaluees can be scheduled");
        }
        if (!isStemOpt(lc.getUserId())) {
            throw new ConflictException(
                    "Intern is not F1 STEM OPT — I-983 not applicable");
        }
        if (hasOpenOfType(lc.getId(), req.evaluationType())) {
            throw new ConflictException(
                    "An open " + req.evaluationType() + " I-983 already exists for this intern");
        }

        I983Evaluation ev = I983Evaluation.builder()
                .internLifecycleId(lc.getId())
                .evaluatorId(caller.getId())
                .evaluationType(req.evaluationType())
                .periodStartDate(req.periodStartDate())
                .periodEndDate(req.periodEndDate())
                .status("SCHEDULED")
                .version(1)
                .createdById(caller.getId())
                .employerSignatureRequired(Boolean.TRUE)
                .studentSignatureRequired(Boolean.TRUE)
                .build();
        I983Evaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "I983_EVALUATION_SCHEDULED",
                saved.getId(), null, Map.of(
                        "evaluationType", req.evaluationType(),
                        "scheduledFor", req.scheduledFor() != null
                                ? req.scheduledFor().toString() : "(none)"));
        fanout.i983Scheduled(saved, lc, caller);
        return toEvaluatorDetail(saved, lc);
    }

    // ── Start ────────────────────────────────────────────────────────────

    @Transactional
    public I983WorkflowDtos.EvaluatorI983Detail start(UUID id, User caller) {
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        requireEvaluatorIs(ev, caller);
        if (!"SCHEDULED".equals(ev.getStatus())) {
            throw new ConflictException(
                    "Can only start from SCHEDULED (current: " + ev.getStatus() + ")");
        }
        ev.setStatus("IN_PROGRESS");
        I983Evaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "I983_EVALUATION_STARTED",
                saved.getId(),
                Map.of("status", "SCHEDULED"),
                Map.of("status", "IN_PROGRESS"));
        return toEvaluatorDetail(saved, lc);
    }

    // ── Save draft ───────────────────────────────────────────────────────

    @Transactional
    public I983WorkflowDtos.EvaluatorI983Detail saveDraft(
            UUID id, I983WorkflowDtos.SaveDraftI983Request req, User caller) {
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        requireEvaluatorIs(ev, caller);
        if (!Set.of("SCHEDULED", "IN_PROGRESS").contains(ev.getStatus())) {
            throw new ConflictException(
                    "Drafts allowed only in SCHEDULED / IN_PROGRESS (current: "
                            + ev.getStatus() + ")");
        }
        if (req != null) {
            if (req.trainingObjectivesProgress() != null) {
                ev.setTrainingObjectivesProgress(trim(req.trainingObjectivesProgress(), 10000));
            }
            if (req.trainingSupervisionProvided() != null) {
                ev.setTrainingSupervisionProvided(trim(req.trainingSupervisionProvided(), 10000));
            }
            if (req.trainingEvaluationOutcomes() != null) {
                ev.setTrainingEvaluationOutcomes(trim(req.trainingEvaluationOutcomes(), 10000));
            }
            if (req.objectivesAchieved() != null) {
                ev.setObjectivesAchieved(trim(req.objectivesAchieved(), 10000));
            }
            if (req.supervisorAssessment() != null) {
                ev.setSupervisorAssessment(trim(req.supervisorAssessment(), 5000));
            }
        }
        if ("SCHEDULED".equals(ev.getStatus())) ev.setStatus("IN_PROGRESS");
        return toEvaluatorDetail(evalRepo.save(ev), lc);
    }

    // ── Publish ──────────────────────────────────────────────────────────

    @Transactional
    public I983WorkflowDtos.EvaluatorI983Detail publish(UUID id, User caller) {
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        requireEvaluatorIs(ev, caller);
        if (!"IN_PROGRESS".equals(ev.getStatus())) {
            throw new ConflictException(
                    "Publish requires IN_PROGRESS (current: " + ev.getStatus() + ")");
        }
        if (textLen(ev.getTrainingObjectivesProgress()) < 100) {
            throw new BadRequestException(
                    "trainingObjectivesProgress must be at least 100 characters");
        }
        if (textLen(ev.getTrainingSupervisionProvided()) < 100) {
            throw new BadRequestException(
                    "trainingSupervisionProvided must be at least 100 characters");
        }
        if (textLen(ev.getTrainingEvaluationOutcomes()) < 100) {
            throw new BadRequestException(
                    "trainingEvaluationOutcomes must be at least 100 characters");
        }
        Instant now = Instant.now();
        ev.setStatus("PUBLISHED");
        ev.setPublishedAt(now);
        I983Evaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "I983_EVALUATION_PUBLISHED",
                saved.getId(),
                Map.of("status", "IN_PROGRESS"),
                Map.of("status", "PUBLISHED",
                        "publishedAt", now.toString()));
        fanout.i983Published(saved, lc, caller);
        return toEvaluatorDetail(saved, lc);
    }

    // ── Acknowledge (intern-side) ────────────────────────────────────────

    @Transactional
    public I983WorkflowDtos.InternI983View acknowledge(
            UUID id, I983WorkflowDtos.AcknowledgeI983Request req, User caller) {
        if (req == null || req.studentTypedSignature() == null
                || req.studentTypedSignature().trim().isEmpty()) {
            throw new BadRequestException("studentTypedSignature is required");
        }
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        if (!caller.getId().equals(lc.getUserId())) {
            throw new ForbiddenException(
                    "Only the evaluated intern may acknowledge this evaluation");
        }
        if (!Set.of("PUBLISHED", "AMENDED").contains(ev.getStatus())) {
            throw new ConflictException(
                    "Acknowledge requires PUBLISHED or AMENDED (current: "
                            + ev.getStatus() + ")");
        }
        Instant now = Instant.now();
        ev.setStatus("ACKNOWLEDGED");
        ev.setAcknowledgedAt(now);
        ev.setStudentTypedSignature(trim(req.studentTypedSignature(), 200));
        if (req.internResponse() != null && !req.internResponse().isBlank()) {
            ev.setInternResponse(trim(req.internResponse(), 2000));
        }
        I983Evaluation saved = evalRepo.save(ev);
        writeAudit(caller, caller.getId(), "I983_EVALUATION_ACKNOWLEDGED",
                saved.getId(),
                Map.of("status", "PUBLISHED"),
                Map.of("status", "ACKNOWLEDGED",
                        "acknowledgedAt", now.toString()));
        fanout.i983Acknowledged(saved, lc, caller);
        return toInternView(saved, lookupName(saved.getEvaluatorId()),
                loadPlanContext(lc));
    }

    // ── Mark DSO submitted ──────────────────────────────────────────────

    @Transactional
    public I983WorkflowDtos.EvaluatorI983Detail markDsoSubmitted(
            UUID id, I983WorkflowDtos.MarkDsoSubmittedRequest req, User caller) {
        if (req == null || req.submissionMethod() == null
                || !SUBMISSION_METHODS.contains(req.submissionMethod())) {
            throw new BadRequestException(
                    "submissionMethod must be one of " + SUBMISSION_METHODS);
        }
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        requireEvaluatorIs(ev, caller);
        if (!Set.of("ACKNOWLEDGED", "AMENDED").contains(ev.getStatus())) {
            throw new ConflictException(
                    "DSO submission can be tracked only after ACKNOWLEDGED "
                            + "(current: " + ev.getStatus() + ")");
        }
        Instant now = Instant.now();
        ev.setDsoSubmittedToSchoolAt(now);
        ev.setDsoSubmissionMethod(req.submissionMethod());
        if (req.submissionNotes() != null) {
            ev.setDsoSubmissionNotes(trim(req.submissionNotes(), 2000));
        }
        I983Evaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "I983_DSO_SUBMITTED",
                saved.getId(), null,
                Map.of("submissionMethod", req.submissionMethod(),
                        "submittedAt", now.toString()));
        fanout.i983DsoSubmitted(saved, lc, caller);
        return toEvaluatorDetail(saved, lc);
    }

    // ── Amend ────────────────────────────────────────────────────────────

    @Transactional
    public I983WorkflowDtos.EvaluatorI983Detail amend(
            UUID id, I983WorkflowDtos.AmendI983Request req, User caller) {
        if (req == null || req.amendmentReason() == null
                || req.amendmentReason().trim().length() < 30) {
            throw new BadRequestException(
                    "amendmentReason must be at least 30 characters");
        }
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (!superAdmin && !caller.getId().equals(ev.getEvaluatorId())) {
            throw new ForbiddenException(
                    "Only the publishing Evaluator can amend this I-983");
        }
        if (!Set.of("PUBLISHED", "ACKNOWLEDGED", "AMENDED").contains(ev.getStatus())) {
            throw new ConflictException(
                    "Amend requires PUBLISHED / ACKNOWLEDGED / AMENDED (current: "
                            + ev.getStatus() + ")");
        }
        int previousVersion = ev.getVersion() != null ? ev.getVersion() : 1;
        Instant now = Instant.now();

        // Apply optional updates.
        if (req.trainingObjectivesProgress() != null) {
            ev.setTrainingObjectivesProgress(trim(req.trainingObjectivesProgress(), 10000));
        }
        if (req.trainingSupervisionProvided() != null) {
            ev.setTrainingSupervisionProvided(trim(req.trainingSupervisionProvided(), 10000));
        }
        if (req.trainingEvaluationOutcomes() != null) {
            ev.setTrainingEvaluationOutcomes(trim(req.trainingEvaluationOutcomes(), 10000));
        }
        if (req.objectivesAchieved() != null) {
            ev.setObjectivesAchieved(trim(req.objectivesAchieved(), 10000));
        }
        if (req.supervisorAssessment() != null) {
            ev.setSupervisorAssessment(trim(req.supervisorAssessment(), 5000));
        }
        ev.setVersion(previousVersion + 1);
        ev.setAmendedAt(now);
        ev.setAmendmentReason(req.amendmentReason().trim());
        ev.setStatus("AMENDED");
        // Reset BOTH student signature and DSO submission — the document
        // materially changed.
        ev.setAcknowledgedAt(null);
        ev.setStudentTypedSignature(null);
        ev.setInternResponse(null);
        ev.setDsoSubmittedToSchoolAt(null);
        ev.setDsoSubmissionMethod(null);
        ev.setDsoSubmissionNotes(null);

        I983Evaluation saved = evalRepo.save(ev);
        writeAudit(caller, lc.getUserId(), "I983_EVALUATION_AMENDED",
                saved.getId(),
                Map.of("version", previousVersion),
                Map.of("status", "AMENDED",
                        "version", saved.getVersion(),
                        "amendmentReason", req.amendmentReason()));
        fanout.i983Amended(saved, lc, caller, req.amendmentReason());
        return toEvaluatorDetail(saved, lc);
    }

    // ── Read endpoints ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public I983WorkflowDtos.EvaluatorI983Detail getEvaluatorDetail(UUID id, User caller) {
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        // Read AND write now share the same EvaluatorScopeGuard predicate.
        // Previously this read had a 3-way fallback (super_admin OR
        // ev.evaluatorId OR lc.evaluatorId) while writes used a strict
        // lc.evaluatorId check, so a user could read an I-983 but not
        // write to it. The guard's null-lc.evaluatorId fallback covers
        // every case the old 3-way OR allowed.
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);
        return toEvaluatorDetail(ev, lc);
    }

    @Transactional(readOnly = true)
    public I983WorkflowDtos.InternI983View getInternView(UUID id, User caller) {
        I983Evaluation ev = mustGet(id);
        InternLifecycle lc = mustGetLifecycle(ev.getInternLifecycleId());
        if (!caller.getId().equals(lc.getUserId())) {
            throw new ForbiddenException(
                    "Only the evaluated intern can view this I-983");
        }
        if (!Set.of("PUBLISHED", "ACKNOWLEDGED", "AMENDED").contains(ev.getStatus())) {
            throw new ConflictException("I-983 is not yet published");
        }
        return toInternView(ev, lookupName(ev.getEvaluatorId()), loadPlanContext(lc));
    }

    @Transactional(readOnly = true)
    public List<I983WorkflowDtos.InternI983Row> listForIntern(User caller) {
        java.util.Optional<InternLifecycle> lcOpt = lifecycleRepo.findByUserId(caller.getId());
        if (lcOpt.isEmpty()) return List.of();
        UUID lifecycleId = lcOpt.get().getId();
        try {
            return jdbc.query(
                    "SELECT ie.id, ie.evaluation_type, ie.status, ie.version, "
                            + "ie.period_start_date, ie.period_end_date, "
                            + "ie.published_at, ie.acknowledged_at, "
                            + "ie.dso_submitted_to_school_at, "
                            + "u.full_name AS evaluator_name "
                            + "FROM i983_evaluations ie "
                            + "LEFT JOIN users u ON u.id = ie.evaluator_id "
                            + "WHERE ie.intern_lifecycle_id = ? "
                            + "  AND ie.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + "ORDER BY ie.published_at DESC NULLS LAST",
                    (rs, n) -> {
                        Date psd = rs.getDate("period_start_date");
                        Date ped = rs.getDate("period_end_date");
                        return new I983WorkflowDtos.InternI983Row(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("evaluator_name"),
                                rs.getString("evaluation_type"),
                                rs.getString("status"),
                                rs.getInt("version"),
                                psd != null ? psd.toLocalDate() : null,
                                ped != null ? ped.toLocalDate() : null,
                                rs.getTimestamp("published_at") != null
                                        ? rs.getTimestamp("published_at").toInstant() : null,
                                rs.getTimestamp("acknowledged_at") != null
                                        ? rs.getTimestamp("acknowledged_at").toInstant() : null,
                                rs.getTimestamp("dso_submitted_to_school_at") != null
                                        ? rs.getTimestamp("dso_submitted_to_school_at").toInstant() : null);
                    },
                    lifecycleId);
        } catch (Exception e) {
            log.warn("[I983.listForIntern] failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<I983WorkflowDtos.InternI983Row> firstUnsigned(User caller) {
        return listForIntern(caller).stream()
                .filter(r -> r.acknowledgedAt() == null)
                .findFirst();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private I983Evaluation mustGet(UUID id) {
        return evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "I-983 evaluation not found: " + id));
    }

    private InternLifecycle mustGetLifecycle(UUID id) {
        return lifecycleRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException(
                "InternLifecycle not found"));
    }

    private void requireOwnership(InternLifecycle lc, User caller) {
        // Delegate to the shared EvaluatorScopeGuard — single-evaluator
        // null-fallback applies uniformly to schedule / saveDraft /
        // publish / amend / markDsoSubmitted.
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);
    }

    private void requireEvaluatorIs(I983Evaluation ev, User caller) {
        // Row-level check now reuses the lifecycle-level predicate so
        // the row's evaluator_id can't lock the current org evaluator
        // out of a row stamped under a prior default account.
        InternLifecycle lc = ev.getInternLifecycleId() != null
                ? lifecycleRepo.findById(ev.getInternLifecycleId()).orElse(null)
                : null;
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);
    }

    private boolean isStemOpt(UUID userId) {
        try {
            String t = jdbc.queryForObject(
                    "SELECT work_auth_type FROM work_authorization_records "
                            + "WHERE user_id = ?",
                    String.class, userId);
            return "F1_STEM_OPT".equalsIgnoreCase(t);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasOpenOfType(UUID lifecycleId, String type) {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM i983_evaluations "
                            + "WHERE intern_lifecycle_id = ? "
                            + "  AND evaluation_type = ? "
                            + "  AND status IN ('SCHEDULED','IN_PROGRESS','PUBLISHED','AMENDED')",
                    Long.class, lifecycleId, type);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String lookupName(UUID userId) {
        if (userId == null) return null;
        try {
            return userRepo.findById(userId).map(User::getFullName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static int textLen(String s) { return s == null ? 0 : s.trim().length(); }

    private I983WorkflowDtos.I983PlanContext loadPlanContext(InternLifecycle lc) {
        try {
            return jdbc.queryForObject(
                    "SELECT ip.id, ip.status, ip.training_start_date, "
                            + "ip.training_end_date, ip.university_name, "
                            + "ip.training_goals_and_objectives, "
                            + "ip.performance_evaluation_method, "
                            + "ip.supervisor_name, ip.supervisor_email "
                            + "FROM i983_plans ip "
                            + "JOIN candidates c ON c.id = ip.candidate_id "
                            + "WHERE c.user_id = ? "
                            + "ORDER BY ip.created_at DESC LIMIT 1",
                    (rs, n) -> {
                        Date tsd = rs.getDate("training_start_date");
                        Date ted = rs.getDate("training_end_date");
                        return new I983WorkflowDtos.I983PlanContext(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("status"),
                                tsd != null ? tsd.toLocalDate() : null,
                                ted != null ? ted.toLocalDate() : null,
                                rs.getString("university_name"),
                                rs.getString("training_goals_and_objectives"),
                                rs.getString("performance_evaluation_method"),
                                rs.getString("supervisor_name"),
                                rs.getString("supervisor_email"));
                    },
                    lc.getUserId());
        } catch (Exception e) {
            return null;
        }
    }

    private I983WorkflowDtos.EvaluatorI983Detail toEvaluatorDetail(
            I983Evaluation ev, InternLifecycle lc) {
        User intern = userRepo.findById(lc.getUserId()).orElse(null);
        return new I983WorkflowDtos.EvaluatorI983Detail(
                ev.getId(), lc.getId(), lc.getUserId(),
                intern != null ? intern.getFullName() : null,
                intern != null ? intern.getEmail() : null,
                lc.getEmployeeId(),
                ev.getEvaluationType(), ev.getStatus(),
                ev.getVersion() != null ? ev.getVersion() : 1,
                ev.getPeriodStartDate(), ev.getPeriodEndDate(),
                ev.getTrainingObjectivesProgress(),
                ev.getTrainingSupervisionProvided(),
                ev.getTrainingEvaluationOutcomes(),
                ev.getObjectivesAchieved(),
                ev.getSupervisorAssessment(),
                lookupName(ev.getEvaluatorId()),
                ev.getPublishedAt(), ev.getAcknowledgedAt(),
                ev.getStudentTypedSignature(), ev.getInternResponse(),
                ev.getDsoSubmittedToSchoolAt(),
                ev.getDsoSubmissionMethod(), ev.getDsoSubmissionNotes(),
                ev.getAmendedAt(), ev.getAmendmentReason(),
                loadPlanContext(lc));
    }

    private I983WorkflowDtos.InternI983View toInternView(
            I983Evaluation ev, String evaluatorName,
            I983WorkflowDtos.I983PlanContext plan) {
        return new I983WorkflowDtos.InternI983View(
                ev.getId(), evaluatorName,
                ev.getEvaluationType(), ev.getStatus(),
                ev.getVersion() != null ? ev.getVersion() : 1,
                ev.getPeriodStartDate(), ev.getPeriodEndDate(),
                ev.getTrainingObjectivesProgress(),
                ev.getTrainingSupervisionProvided(),
                ev.getTrainingEvaluationOutcomes(),
                ev.getObjectivesAchieved(),
                ev.getSupervisorAssessment(),
                ev.getPublishedAt(), ev.getAcknowledgedAt(),
                ev.getStudentTypedSignature(), ev.getInternResponse(),
                ev.getDsoSubmittedToSchoolAt(), ev.getDsoSubmissionMethod(),
                ev.getAmendedAt(), plan);
    }

    private void writeAudit(User caller, UUID subjectUserId, String action,
                             UUID entityId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            Map<String, Object> beforeM = before != null ? new LinkedHashMap<>(before) : null;
            Map<String, Object> afterM = after != null ? new LinkedHashMap<>(after) : null;
            AuditLog row = AuditLog.builder()
                    .userId(caller != null ? caller.getId() : null)
                    .subjectUserId(subjectUserId)
                    .entityType("I983Evaluation")
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(beforeM != null
                            ? objectMapper.writeValueAsString(beforeM) : null)
                    .afterJson(afterM != null
                            ? objectMapper.writeValueAsString(afterM) : null)
                    .build();
            auditRepo.save(row);
        } catch (Exception e) {
            log.warn("[I983Workflow] audit write failed: {}", e.getMessage());
        }
    }
}
