package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.evaluation.CreateEvaluationRequest;
import com.anvicorp.api.dto.evaluation.EvaluationContextResponse;
import com.anvicorp.api.dto.evaluation.EvaluationResponse;
import com.anvicorp.api.dto.evaluation.EvaluationRubricScoreResponse;
import com.anvicorp.api.dto.evaluation.EvaluationSelfReviewResponse;
import com.anvicorp.api.dto.evaluation.SubmitSelfReviewRequest;
import com.anvicorp.api.dto.evaluation.UpdateEvaluationRequest;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Evaluation;
import com.anvicorp.api.entity.EvaluationRubricScore;
import com.anvicorp.api.entity.EvaluationSelfReview;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.EvaluationStatus;
import com.anvicorp.api.enums.EvaluationType;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.TimesheetStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WeeklyReportStatus;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.EvaluationRepository;
import com.anvicorp.api.repository.EvaluationRubricScoreRepository;
import com.anvicorp.api.repository.EvaluationSelfReviewRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic intern evaluations. Supervisor authors; intern reads finalized
 * rows; HR reads (any type, since I-983 is the legally-relevant
 * subset and the rest is HR-relevant too).
 *
 * <h2>Gates</h2>
 * <ul>
 *   <li>Write (create / update / finalize): the engagement's supervisor OR
 *       SUPER_ADMIN.</li>
 *   <li>Read /intern/{id}: that engagement's supervisor OR HR
 *       OR SUPER_ADMIN.</li>
 *   <li>Read /me (intern): only FINALIZED rows for the caller's own
 *       Candidate.</li>
 *   <li>Self-review (intern): only when the parent evaluation is DRAFT and
 *       its type is one of {@code I983_*}.</li>
 * </ul>
 *
 * <h2>FINALIZE lock</h2>
 * {@code PUT /{id}} and rubric changes 409 once the evaluation is FINALIZED.
 * The {@code /finalize} call itself is idempotent (re-clicks return the row
 * with no new audit).
 *
 * <h2>Audit</h2>
 * EVALUATION_CREATED / EVALUATION_FINALIZED / EVALUATION_SELF_SUBMITTED.
 * Best-effort writes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationService {

    private static final Set<EvaluationType> SELF_REVIEW_TYPES =
            EnumSet.of(EvaluationType.I983_12MO, EvaluationType.I983_FINAL);

    private final EvaluationRepository evaluationRepository;
    private final EvaluationRubricScoreRepository rubricRepository;
    private final EvaluationSelfReviewRepository selfReviewRepository;
    private final CandidateRepository candidateRepository;
    private final EngagementRepository engagementRepository;
    private final ProjectRepository projectRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final TimesheetRepository timesheetRepository;
    private final AuditLogRepository auditLogRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final ObjectMapper objectMapper;
    private final com.anvicorp.api.notification.NotificationService notificationService;

    // ── Supervisor write paths ──────────────────────────────────────────────

    @Transactional
    public EvaluationResponse create(CreateEvaluationRequest req, User actor) {
        Candidate intern = candidateRepository.findById(req.getCandidateId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + req.getCandidateId()));
        Engagement engagement = pickEngagement(intern.getId())
                .orElseThrow(() -> new BadRequestException(
                        "Intern has no engagement — can't evaluate yet."));
        ensureSupervisorOwnsEngagement(engagement, actor);

        Evaluation evaluation = Evaluation.builder()
                .intern(intern)
                .engagement(engagement)
                .evaluator(actor)
                .type(req.getType())
                .periodStart(req.getPeriodStart())
                .periodEnd(req.getPeriodEnd())
                .status(EvaluationStatus.DRAFT)
                .build();
        evaluation = evaluationRepository.save(evaluation);
        replaceRubric(evaluation, req.getRubric());

        writeAudit(evaluation.getId(), "EVALUATION_CREATED", actor.getId(), Map.of(
                "type", evaluation.getType().name(),
                "candidateId", intern.getId(),
                "engagementId", engagement.getId()));

        return toResponse(reload(evaluation.getId()), true);
    }

    @Transactional
    public EvaluationResponse update(UUID evaluationId, UpdateEvaluationRequest req, User actor) {
        Evaluation evaluation = evaluationRepository.findByIdWithGraph(evaluationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + evaluationId));
        // IDOR guard: resolve the intern's lifecycle from the evaluation and
        // require the caller be the assigned Evaluator (or SUPER_ADMIN).
        // Unowned callers see a 404, not a 403 — cross-evaluator probing
        // can't confirm the row exists.
        ensureEvaluatorOwnsOr404(evaluation, actor, evaluationId);
        ensureWriter(evaluation, actor);
        if (evaluation.getStatus() == EvaluationStatus.FINALIZED) {
            throw new ConflictException(
                    "This evaluation is FINALIZED and locked.");
        }

        if (req.getPeriodStart() != null) evaluation.setPeriodStart(req.getPeriodStart());
        if (req.getPeriodEnd() != null) evaluation.setPeriodEnd(req.getPeriodEnd());
        if (req.getOverallRating() != null) {
            evaluation.setOverallRating(clamp(req.getOverallRating()));
        }
        if (req.getStrengths() != null) evaluation.setStrengths(req.getStrengths());
        if (req.getAreasForImprovement() != null) {
            evaluation.setAreasForImprovement(req.getAreasForImprovement());
        }
        if (req.getComments() != null) evaluation.setComments(req.getComments());
        if (req.getRecommendation() != null) evaluation.setRecommendation(req.getRecommendation());

        evaluation = evaluationRepository.save(evaluation);
        if (req.getRubric() != null) {
            replaceRubric(evaluation, req.getRubric());
        }
        return toResponse(reload(evaluation.getId()), true);
    }

    @Transactional
    public EvaluationResponse finalize(UUID evaluationId, User actor) {
        Evaluation evaluation = evaluationRepository.findByIdWithGraph(evaluationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + evaluationId));
        // IDOR guard mirror of update(): un-assigned evaluators get 404.
        ensureEvaluatorOwnsOr404(evaluation, actor, evaluationId);
        ensureWriter(evaluation, actor);
        if (evaluation.getStatus() == EvaluationStatus.FINALIZED) {
            return toResponse(evaluation, true); // idempotent
        }
        if (evaluation.getOverallRating() == null) {
            throw new BadRequestException(
                    "Set an overall rating before finalizing.");
        }
        evaluation.setStatus(EvaluationStatus.FINALIZED);
        evaluation.setFinalizedAt(Instant.now());
        evaluation = evaluationRepository.save(evaluation);

        writeAudit(evaluation.getId(), "EVALUATION_FINALIZED", actor.getId(), Map.of(
                "type", evaluation.getType().name(),
                "candidateId", evaluation.getIntern().getId()));

        // Batch-3 — intern gets a "your evaluation is ready" email with
        // rating + supervisor name. Best-effort.
        Evaluation reloaded = reload(evaluation.getId());
        try {
            notificationService.sendEvaluationFinalized(reloaded);
        } catch (Exception e) {
            log.warn("EVALUATION_FINALIZED notify failed (non-fatal) for {}: {}",
                    reloaded.getId(), e.getMessage());
        }
        return toResponse(reloaded, true);
    }

    // ── Read paths ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EvaluationResponse> listForIntern(UUID candidateId, User actor) {
        Candidate intern = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));
        ensureCanRead(intern, actor);
        return evaluationRepository.findByInternIdWithGraph(intern.getId()).stream()
                .map(e -> toResponse(e, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EvaluationResponse> listMine(User candidateUser) {
        // Auth check — the @PreAuthorize already requires INTERN, this
        // belt-and-braces ensures the user has a Candidate row before any
        // data is returned.
        candidateRepository.findByUserId(candidateUser.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Evaluations are visible to interns only."));
        return evaluationRepository
                .findFinalizedByCandidateUserIdWithGraph(candidateUser.getId(),
                        EvaluationStatus.FINALIZED)
                .stream()
                .map(e -> toResponse(e, false))
                .toList();
    }

    /** Supervisor's authored evaluations — drives the supervisor board. */
    @Transactional(readOnly = true)
    public List<EvaluationResponse> listAuthored(User actor) {
        return evaluationRepository.findByEvaluatorIdWithGraph(actor.getId()).stream()
                .map(e -> toResponse(e, true))
                .toList();
    }

    /**
     * Intern's self-review surface — DRAFT I-983 evaluations the intern owns,
     * so they can submit a reflection before the supervisor finalizes. Read
     * gate matches {@link #listMine}: must have a Candidate row.
     */
    @Transactional(readOnly = true)
    public List<EvaluationResponse> listSelfReviewable(User candidateUser) {
        candidateRepository.findByUserId(candidateUser.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Evaluations are visible to interns only."));
        return evaluationRepository
                .findSelfReviewableDraftsByCandidateUserIdWithGraph(candidateUser.getId())
                .stream()
                .map(e -> toResponse(e, false))
                .toList();
    }

    // ── Intern self-review ──────────────────────────────────────────────────

    @Transactional
    public EvaluationResponse submitSelfReview(UUID evaluationId,
                                               SubmitSelfReviewRequest req,
                                               User candidateUser) {
        Evaluation evaluation = evaluationRepository.findByIdWithGraph(evaluationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + evaluationId));
        Candidate candidate = candidateRepository.findByUserId(candidateUser.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Self-review is available to interns only."));
        if (!evaluation.getIntern().getId().equals(candidate.getId())) {
            // Don't leak existence.
            throw new ResourceNotFoundException(
                    "Evaluation not found: " + evaluationId);
        }
        if (!SELF_REVIEW_TYPES.contains(evaluation.getType())) {
            throw new BadRequestException(
                    "Self-review isn't available for this evaluation type.");
        }
        if (evaluation.getStatus() == EvaluationStatus.FINALIZED) {
            throw new ConflictException(
                    "This evaluation is finalized; self-review window is closed.");
        }

        EvaluationSelfReview existing = selfReviewRepository
                .findByEvaluationId(evaluation.getId())
                .orElseGet(() -> EvaluationSelfReview.builder()
                        .evaluation(evaluation).build());
        if (req != null) {
            if (req.getReflection() != null) existing.setReflection(req.getReflection());
            if (req.getSelfOverallRating() != null) {
                existing.setSelfOverallRating(clamp(req.getSelfOverallRating()));
            }
            if (req.getSelfTechnicalRating() != null) {
                existing.setSelfTechnicalRating(clamp(req.getSelfTechnicalRating()));
            }
            if (req.getSelfGrowthRating() != null) {
                existing.setSelfGrowthRating(clamp(req.getSelfGrowthRating()));
            }
        }
        existing.setSubmittedAt(Instant.now());
        selfReviewRepository.save(existing);

        writeAudit(evaluation.getId(), "EVALUATION_SELF_SUBMITTED", candidateUser.getId(),
                Map.of("type", evaluation.getType().name()));

        return toResponse(reload(evaluation.getId()), false);
    }

    // ── Gates ───────────────────────────────────────────────────────────────

    private Optional<Engagement> pickEngagement(UUID candidateId) {
        return engagementRepository.findByCandidateId(candidateId).stream()
                .filter(e -> e.getStatus() != EngagementStatus.TERMINATED)
                .max(Comparator.comparing(Engagement::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    // Role-based gates — per-engagement supervisor FK is informational
    // metadata, not a permission boundary.
    private void ensureSupervisorOwnsEngagement(Engagement engagement, User actor) {
        ensureTechnicalSupervisorRole(actor);
    }

    /**
     * IDOR guard for the writer paths (update / finalize). Resolves the
     * intern's user id from the evaluation's Candidate, loads the
     * lifecycle, and enforces "caller is the assigned evaluator on the
     * lifecycle OR is the original evaluator that authored the row".
     * SUPER_ADMIN bypasses. On mismatch throws
     * {@link ResourceNotFoundException} (not 403) so cross-evaluator
     * probing can't confirm the row exists.
     *
     * <p>Null lifecycle.evaluator_id is the single-evaluator-org default
     * — mirrors {@link EvaluatorScopeGuard}'s fallback so we don't lock
     * out the current org evaluator on rows created under a prior
     * default account.</p>
     *
     * <p>Reads (listForIntern / listMine / listAuthored /
     * listSelfReviewable) are intentionally left broader per the audit
     * — the reviewer/HR audit trail benefits from visibility.</p>
     */
    private void ensureEvaluatorOwnsOr404(Evaluation evaluation, User actor, UUID evaluationId) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(actor)) return;
        // Original evaluator that authored the row always keeps write
        // access (matches ensureWriter's same-author allowance).
        if (evaluation.getEvaluator() != null
                && evaluation.getEvaluator().getId().equals(actor.getId())) {
            return;
        }
        Candidate intern = evaluation.getIntern();
        UUID internUserId = intern != null && intern.getUser() != null
                ? intern.getUser().getId() : null;
        InternLifecycle lc = internUserId != null
                ? internLifecycleRepository.findByUserId(internUserId).orElse(null)
                : null;
        // Single-evaluator-org fallback: null evaluator_id = any
        // TRAINER/EVALUATOR is de-facto owner (mirrors EvaluatorScopeGuard).
        if (lc == null || lc.getEvaluatorId() == null) {
            log.debug("[EvaluationService] null evaluator_id or lifecycle for evaluation={} — "
                    + "allowing caller {} as de-facto owner",
                    evaluationId, actor.getId());
            return;
        }
        if (!actor.getId().equals(lc.getEvaluatorId())) {
            log.warn("[IDOR-guard] evaluation.write caller={} resource={} lifecycle={} reason=not-assigned-evaluator",
                    actor.getId(), evaluationId, lc.getId());
            throw new ResourceNotFoundException(
                    "Evaluation not found: " + evaluationId);
        }
    }

    private void ensureWriter(Evaluation evaluation, User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(actor)) return;
        // The original evaluator OR any TECHNICAL_EVALUATOR can write.
        if (evaluation.getEvaluator() != null
                && evaluation.getEvaluator().getId().equals(actor.getId())) {
            return;
        }
        ensureTechnicalSupervisorRole(actor);
    }

    private void ensureCanRead(Candidate intern, User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(actor)) return;
        if (actor.getRoles() == null) {
            throw new ForbiddenException(
                    "Only this intern's evaluator, HR, or SUPER_ADMIN may view their evaluations.");
        }
        if (actor.getRoles().contains(UserRole.ERM)
                || actor.getRoles().contains(UserRole.TRAINER)
                || actor.getRoles().contains(UserRole.REPORTING_MANAGER)) {
            return;
        }
        throw new ForbiddenException(
                "Only this intern's evaluator, HR, or SUPER_ADMIN may view their evaluations.");
    }

    private static void ensureTechnicalSupervisorRole(User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(actor)) return;
        if (actor.getRoles() != null
                && actor.getRoles().contains(UserRole.TRAINER)) {
            return;
        }
        throw new ForbiddenException(
                "Only TECHNICAL_EVALUATOR or SUPER_ADMIN may perform this action.");
    }

    private static boolean isSuperAdmin(User u) {
        return u.getRoles() != null && u.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    private static Integer clamp(Integer raw) {
        if (raw == null) return null;
        return Math.max(1, Math.min(5, raw));
    }

    // ── Rubric handling ─────────────────────────────────────────────────────

    private void replaceRubric(Evaluation evaluation,
                               List<CreateEvaluationRequest.RubricScoreInput> rows) {
        rubricRepository.deleteByEvaluationId(evaluation.getId());
        if (rows == null || rows.isEmpty()) return;
        for (CreateEvaluationRequest.RubricScoreInput row : rows) {
            if (row == null || row.getCriterion() == null || row.getScore() == null) continue;
            rubricRepository.save(EvaluationRubricScore.builder()
                    .evaluation(evaluation)
                    .criterion(row.getCriterion())
                    .score(clamp(row.getScore()))
                    .note(row.getNote())
                    .build());
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private Evaluation reload(UUID id) {
        return evaluationRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + id));
    }

    private EvaluationResponse toResponse(Evaluation e, boolean withContext) {
        List<EvaluationRubricScore> rubricRows = rubricRepository
                .findByEvaluationId(e.getId());
        EvaluationSelfReview self = selfReviewRepository
                .findByEvaluationId(e.getId()).orElse(null);
        Candidate intern = e.getIntern();
        User internUser = intern != null ? intern.getUser() : null;
        EvaluationContextResponse context = withContext
                ? buildContext(intern, e.getPeriodStart(), e.getPeriodEnd()) : null;

        return EvaluationResponse.builder()
                .id(e.getId())
                .internCandidateId(intern != null ? intern.getId() : null)
                .internName(internUser != null ? internUser.getFullName() : null)
                .engagementId(e.getEngagement() != null ? e.getEngagement().getId() : null)
                .evaluatorId(e.getEvaluator() != null ? e.getEvaluator().getId() : null)
                .evaluatorName(e.getEvaluator() != null ? e.getEvaluator().getFullName() : null)
                .type(e.getType())
                .periodStart(e.getPeriodStart())
                .periodEnd(e.getPeriodEnd())
                .overallRating(e.getOverallRating())
                .strengths(e.getStrengths())
                .areasForImprovement(e.getAreasForImprovement())
                .comments(e.getComments())
                .recommendation(e.getRecommendation())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .finalizedAt(e.getFinalizedAt())
                .updatedAt(e.getUpdatedAt())
                .rubric(rubricRows.stream().map(this::toRubricResponse).toList())
                .selfReview(self != null ? toSelfReviewResponse(self) : null)
                .context(context)
                .build();
    }

    private EvaluationRubricScoreResponse toRubricResponse(EvaluationRubricScore r) {
        return EvaluationRubricScoreResponse.builder()
                .id(r.getId())
                .criterion(r.getCriterion())
                .score(r.getScore())
                .note(r.getNote())
                .build();
    }

    private EvaluationSelfReviewResponse toSelfReviewResponse(EvaluationSelfReview s) {
        return EvaluationSelfReviewResponse.builder()
                .id(s.getId())
                .reflection(s.getReflection())
                .selfOverallRating(s.getSelfOverallRating())
                .selfTechnicalRating(s.getSelfTechnicalRating())
                .selfGrowthRating(s.getSelfGrowthRating())
                .submittedAt(s.getSubmittedAt())
                .build();
    }

    // ── Context block ───────────────────────────────────────────────────────

    private EvaluationContextResponse buildContext(Candidate intern,
                                                   LocalDate periodStart,
                                                   LocalDate periodEnd) {
        if (intern == null || intern.getId() == null) return null;
        UUID candidateId = intern.getId();

        // COMPLETED projects within the period (loose match — if either bound
        // is null we still show the row).
        List<Project> projects = projectRepository.findByInternIdWithGraph(candidateId);
        List<EvaluationContextResponse.CompletedProjectMini> completedProjects = new ArrayList<>();
        for (Project p : projects) {
            if (p.getStatus() != ProjectStatus.COMPLETED) continue;
            LocalDate completedOn = p.getCompletedAt() != null
                    ? p.getCompletedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    : null;
            if (!withinPeriod(completedOn, periodStart, periodEnd)) continue;
            completedProjects.add(EvaluationContextResponse.CompletedProjectMini.builder()
                    .id(p.getId())
                    .title(p.getTitle())
                    .completedDate(completedOn)
                    .build());
        }

        // Weekly reports — counts in period (filter by weekStart).
        List<WeeklyReport> reports = weeklyReportRepository.findByInternIdWithGraph(candidateId);
        long total = 0, approved = 0, returned = 0, pending = 0;
        for (WeeklyReport r : reports) {
            if (!withinPeriod(r.getWeekStart(), periodStart, periodEnd)) continue;
            total++;
            WeeklyReportStatus s = r.getStatus();
            if (s == WeeklyReportStatus.APPROVED) approved++;
            else if (s == WeeklyReportStatus.RETURNED) returned++;
            else if (s == WeeklyReportStatus.SUBMITTED || s == WeeklyReportStatus.DRAFT) pending++;
        }

        // Timesheets — count + approved hours in period.
        List<Timesheet> sheets = timesheetRepository.findForIntern(candidateId);
        long sheetTotal = 0, sheetApproved = 0;
        BigDecimal hours = BigDecimal.ZERO;
        for (Timesheet t : sheets) {
            if (!withinPeriod(t.getWeekStart(), periodStart, periodEnd)) continue;
            sheetTotal++;
            if (t.getStatus() == TimesheetStatus.APPROVED) {
                sheetApproved++;
                if (t.getHours() != null) hours = hours.add(t.getHours());
            }
        }

        return EvaluationContextResponse.builder()
                .completedProjects(completedProjects)
                .reportStats(EvaluationContextResponse.ReportStats.builder()
                        .totalCount(total)
                        .approvedCount(approved)
                        .returnedCount(returned)
                        .pendingCount(pending)
                        .build())
                .timesheetStats(EvaluationContextResponse.TimesheetStats.builder()
                        .totalCount(sheetTotal)
                        .approvedCount(sheetApproved)
                        .approvedHours(hours.setScale(2, RoundingMode.HALF_UP).toPlainString())
                        .build())
                .build();
    }

    private boolean withinPeriod(LocalDate when, LocalDate start, LocalDate end) {
        if (when == null) return start == null && end == null;
        if (start != null && when.isBefore(start)) return false;
        if (end != null && when.isAfter(end)) return false;
        return true;
    }

    // ── Audit ───────────────────────────────────────────────────────────────

    private void writeAudit(UUID evaluationId, String action, UUID userId,
                            Map<String, Object> snapshot) {
        Map<String, Object> after = snapshot != null
                ? new LinkedHashMap<>(snapshot) : new LinkedHashMap<>();
        AuditLog entry = AuditLog.builder()
                .entityType("Evaluation")
                .entityId(evaluationId)
                .action(action)
                .userId(userId)
                .afterJson(serializeJson(after))
                .build();
        try {
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write {} audit row (non-fatal): {}", action, e.getMessage());
        }
    }

    private String serializeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize evaluation audit snapshot: {}", e.getMessage());
            return String.valueOf(snapshot);
        }
    }

}
