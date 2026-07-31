package com.anvicorp.api.evaluator.perproject;

import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.evaluator.EvaluationNotificationFanout;
import com.anvicorp.api.evaluator.EvaluatorScopeGuard;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Evaluator per-project workflow — parallel to the monthly cadence.
 * Reads the auto-drafted POST_PROJECT rows created by
 * {@code PostProjectEvaluationListener}, exposes them as a queue, plus
 * intern-timeline + per-project context + per-project session
 * scheduling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PerProjectService {

    private final JdbcTemplate jdbc;
    private final InternEvaluationRepository evalRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final DocumentRepository documentRepository;
    private final MeetingProvider meetingProvider;
    private final EvaluationNotificationFanout fanout;
    private final EvaluatorScopeGuard evaluatorScopeGuard;

    // ── §1 Awaiting-evaluation queue ────────────────────────────────────

    @Transactional(readOnly = true)
    public PerProjectDtos.AwaitingEvaluationResponse listAwaiting(User caller) {
        requireEvaluatorOrSuperAdmin(caller);
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        UUID evaluatorId = caller.getId();

        // Join intern_evaluations (POST_PROJECT, still-actionable) with
        // projects (the linked project) + lifecycles + users. Also computes
        // per-intern project sequence via a window function so the
        // frontend can render "1st project", "2nd project", etc.
        String sql =
                "WITH ranked AS ( "
                        + "  SELECT p.id AS project_id, p.intern_lifecycle_id, "
                        + "         ROW_NUMBER() OVER ( "
                        + "           PARTITION BY p.intern_lifecycle_id "
                        + "           ORDER BY COALESCE(p.start_date, p.created_at::date), p.created_at "
                        + "         ) AS project_seq "
                        + "    FROM projects p "
                        + "   WHERE p.intern_lifecycle_id IS NOT NULL "
                        + ") "
                        + "SELECT ev.id AS evaluation_id, ev.status AS eval_status, "
                        + "       ev.linked_project_id, ev.intern_lifecycle_id, "
                        + "       il.user_id AS intern_user_id, u.full_name AS intern_name, "
                        + "       il.employee_id, "
                        + "       p.title AS project_title, p.tech_stack, "
                        + "       COALESCE(rk.project_seq, 1) AS project_seq, "
                        + "       p.due_date, p.completed_at, p.submitted_at, "
                        + "       ev.scheduled_for "
                        + "  FROM intern_evaluations ev "
                        + "  JOIN projects p ON p.id = ev.linked_project_id "
                        + "  JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                        + "  JOIN users u ON u.id = il.user_id "
                        + "  LEFT JOIN ranked rk ON rk.project_id = p.id "
                        + " WHERE ev.evaluation_type = 'POST_PROJECT' "
                        + "   AND ev.status IN ('DRAFT','SCHEDULED','IN_PROGRESS') "
                        + (orgWide ? "" : "   AND (ev.evaluator_id = ? OR ev.evaluator_id IS NULL) ")
                        + " ORDER BY p.completed_at ASC NULLS LAST, ev.created_at ASC";

        List<PerProjectDtos.AwaitingEvaluationRow> rows = new ArrayList<>();
        try {
            org.springframework.jdbc.core.RowMapper<PerProjectDtos.AwaitingEvaluationRow> mapper =
                    (rs, n) -> {
                        Instant completedAt = rs.getTimestamp("completed_at") != null
                                ? rs.getTimestamp("completed_at").toInstant() : null;
                        Long hoursWaiting = completedAt != null
                                ? ChronoUnit.HOURS.between(completedAt, Instant.now())
                                : null;
                        return new PerProjectDtos.AwaitingEvaluationRow(
                                UUID.fromString(rs.getString("evaluation_id")),
                                rs.getString("eval_status"),
                                UUID.fromString(rs.getString("linked_project_id")),
                                UUID.fromString(rs.getString("intern_lifecycle_id")),
                                UUID.fromString(rs.getString("intern_user_id")),
                                rs.getString("intern_name"),
                                rs.getString("employee_id"),
                                rs.getString("project_title"),
                                rs.getString("tech_stack"),
                                rs.getInt("project_seq"),
                                rs.getDate("due_date") != null ? rs.getDate("due_date").toLocalDate() : null,
                                completedAt,
                                rs.getTimestamp("submitted_at") != null
                                        ? rs.getTimestamp("submitted_at").toInstant() : null,
                                rs.getTimestamp("scheduled_for") != null
                                        ? rs.getTimestamp("scheduled_for").toInstant() : null,
                                hoursWaiting);
                    };
            rows = orgWide
                    ? jdbc.query(sql, mapper)
                    : jdbc.query(sql, mapper, evaluatorId);
        } catch (Exception e) {
            log.warn("[PerProject] awaiting-eval query failed: {}", e.getMessage());
        }
        return new PerProjectDtos.AwaitingEvaluationResponse(rows, rows.size());
    }

    // ── §3 Intern project timeline ──────────────────────────────────────

    @Transactional(readOnly = true)
    public PerProjectDtos.ProjectTimelineResponse getInternTimeline(
            UUID lifecycleId, User caller) {
        InternLifecycle lc = lifecycleRepo.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);

        String sql =
                "WITH ranked AS ( "
                        + "  SELECT p.id AS project_id, "
                        + "         ROW_NUMBER() OVER ( "
                        + "           PARTITION BY p.intern_lifecycle_id "
                        + "           ORDER BY COALESCE(p.start_date, p.created_at::date), p.created_at "
                        + "         ) AS project_seq "
                        + "    FROM projects p "
                        + "   WHERE p.intern_lifecycle_id = ? "
                        + ") "
                        + "SELECT p.id AS project_id, rk.project_seq, "
                        + "       p.title, p.tech_stack, p.status AS project_status, "
                        + "       p.start_date, p.due_date, p.submitted_at, p.completed_at, "
                        + "       ev.id AS evaluation_id, ev.status AS eval_status, "
                        + "       ev.scheduled_for AS eval_scheduled_for, "
                        + "       ev.timezone AS eval_timezone, "
                        + "       ev.published_at AS eval_published_at, "
                        + "       ev.overall_score, ev.recommendation "
                        + "  FROM projects p "
                        + "  JOIN ranked rk ON rk.project_id = p.id "
                        + "  LEFT JOIN intern_evaluations ev "
                        + "         ON ev.linked_project_id = p.id "
                        + "        AND ev.evaluation_type = 'POST_PROJECT' "
                        + " WHERE p.intern_lifecycle_id = ? "
                        + " ORDER BY rk.project_seq ASC";

        List<PerProjectDtos.ProjectTimelineEntry> entries = new ArrayList<>();
        try {
            org.springframework.jdbc.core.RowMapper<PerProjectDtos.ProjectTimelineEntry> mapper =
                    (rs, n) -> {
                        String projStatus = rs.getString("project_status");
                        String evalStatus = rs.getString("eval_status");
                        Integer overallScore = (Integer) rs.getObject("overall_score");
                        String uiState = deriveUiState(projStatus, evalStatus);
                        String evalIdRaw = rs.getString("evaluation_id");
                        return new PerProjectDtos.ProjectTimelineEntry(
                                UUID.fromString(rs.getString("project_id")),
                                rs.getInt("project_seq"),
                                rs.getString("title"),
                                rs.getString("tech_stack"),
                                projStatus,
                                rs.getDate("start_date") != null ? rs.getDate("start_date").toLocalDate() : null,
                                rs.getDate("due_date") != null ? rs.getDate("due_date").toLocalDate() : null,
                                rs.getTimestamp("submitted_at") != null
                                        ? rs.getTimestamp("submitted_at").toInstant() : null,
                                rs.getTimestamp("completed_at") != null
                                        ? rs.getTimestamp("completed_at").toInstant() : null,
                                evalIdRaw != null ? UUID.fromString(evalIdRaw) : null,
                                evalStatus,
                                rs.getTimestamp("eval_scheduled_for") != null
                                        ? rs.getTimestamp("eval_scheduled_for").toInstant() : null,
                                rs.getString("eval_timezone"),
                                rs.getTimestamp("eval_published_at") != null
                                        ? rs.getTimestamp("eval_published_at").toInstant() : null,
                                overallScore,
                                rs.getString("recommendation"),
                                uiState);
                    };
            entries = jdbc.query(sql, mapper, lifecycleId, lifecycleId);
        } catch (Exception e) {
            log.warn("[PerProject] timeline query failed for lc={}: {}",
                    lifecycleId, e.getMessage());
        }

        int total = entries.size();
        int evaluated = (int) entries.stream()
                .filter(e -> "EVALUATED".equals(e.uiState()) || "AMENDED".equals(e.uiState()))
                .count();
        int awaiting = (int) entries.stream()
                .filter(e -> "AWAITING_EVAL".equals(e.uiState()))
                .count();
        int inProgress = (int) entries.stream()
                .filter(e -> "IN_PROGRESS".equals(e.uiState()))
                .count();

        User intern = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        return new PerProjectDtos.ProjectTimelineResponse(
                lc.getId(), lc.getUserId(),
                intern != null ? intern.getFullName() : null,
                lc.getEmployeeId(),
                total, evaluated, awaiting, inProgress,
                entries);
    }

    private static String deriveUiState(String projectStatus, String evalStatus) {
        if (evalStatus != null && ("PUBLISHED".equals(evalStatus)
                || "ACKNOWLEDGED".equals(evalStatus))) {
            return "EVALUATED";
        }
        if ("AMENDED".equals(evalStatus)) return "AMENDED";
        if (projectStatus != null && "COMPLETED".equals(projectStatus)) {
            // Completed project + no published eval yet
            return "AWAITING_EVAL";
        }
        return "IN_PROGRESS";
    }

    // ── §4 Project context — the hub's project panel ────────────────────

    @Transactional(readOnly = true)
    public PerProjectDtos.ProjectContext getProjectContext(UUID projectId, User caller) {
        requireEvaluatorOrSuperAdmin(caller);
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        String reviewerName = null;
        if (p.getReviewedBy() != null && p.getReviewedBy().getId() != null) {
            reviewerName = userRepository.findById(p.getReviewedBy().getId())
                    .map(User::getFullName).orElse(null);
        }
        return new PerProjectDtos.ProjectContext(
                p.getId(),
                p.getTitle() != null ? p.getTitle() : p.getName(),
                p.getDescription(),
                p.getDeliverables(),
                p.getRequirements(),
                p.getObjectives(),
                p.getInstructions(),
                p.getTechStack(),
                p.getDifficulty() != null ? p.getDifficulty().name() : null,
                p.getProjectNumber() != null ? p.getProjectNumber().intValue() : null,
                p.getMonthYear(),
                p.getStatus() != null ? p.getStatus().name() : null,
                p.getStartDate(),
                p.getDueDate(),
                p.getStartedAt(),
                p.getSubmittedAt(),
                p.getCompletedAt(),
                p.getReviewNotes(),
                reviewerName);
    }

    // ── §4 Recent weekly reports for an intern ──────────────────────────

    @Transactional(readOnly = true)
    public PerProjectDtos.WeeklyReportsResponse getInternWeeklyReports(
            UUID lifecycleId, User caller, int limit) {
        InternLifecycle lc = lifecycleRepo.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);
        // Cap at 100 — one intern year is ~52 reports; 100 comfortably
        // covers a full engagement even with resubmissions. Bumped from
        // 20 so the evaluee-detail hub can render the intern's full
        // journey without pagination.
        int safeLimit = Math.max(1, Math.min(limit, 100));

        // Weekly reports are keyed by candidate/intern id (not lifecycle).
        // Query the intern (candidate) id via the JDBC path that avoids
        // pulling the full graph — the panel only needs a summary.
        UUID candidateInternId = null;
        try {
            candidateInternId = jdbc.queryForObject(
                    "SELECT i.id FROM candidates i WHERE i.user_id = ?",
                    UUID.class, lc.getUserId());
        } catch (Exception ignored) {}
        if (candidateInternId == null) {
            return new PerProjectDtos.WeeklyReportsResponse(lc.getUserId(), List.of());
        }
        List<WeeklyReport> reports;
        try {
            reports = weeklyReportRepository.findByInternIdWithGraph(candidateInternId);
        } catch (Exception e) {
            log.warn("[PerProject] weekly-reports query failed: {}", e.getMessage());
            reports = List.of();
        }
        // Bulk-fetch attachment Document rows so the summary can carry
        // filename/mime without a per-row lookup — one query, ≤100 ids.
        java.util.Set<UUID> attachmentIds = new java.util.HashSet<>();
        for (WeeklyReport r : reports) {
            if (attachmentIds.size() >= safeLimit) break;
            if (r.getAttachmentDocumentId() != null) {
                attachmentIds.add(r.getAttachmentDocumentId());
            }
        }
        java.util.Map<UUID, com.anvicorp.api.entity.Document> attachmentsById = new java.util.HashMap<>();
        if (!attachmentIds.isEmpty()) {
            try {
                for (com.anvicorp.api.entity.Document d
                        : documentRepository.findAllById(attachmentIds)) {
                    if (d.getDeletedAt() == null) attachmentsById.put(d.getId(), d);
                }
            } catch (Exception e) {
                log.warn("[PerProject] weekly-report attachment fetch failed (non-fatal): {}",
                        e.getMessage());
            }
        }

        List<PerProjectDtos.WeeklyReportSummary> items = new ArrayList<>();
        for (WeeklyReport r : reports) {
            if (items.size() >= safeLimit) break;
            UUID docId = r.getAttachmentDocumentId();
            com.anvicorp.api.entity.Document doc = docId != null ? attachmentsById.get(docId) : null;
            items.add(new PerProjectDtos.WeeklyReportSummary(
                    r.getId(),
                    r.getWeekStart(),
                    r.getStatus() != null ? r.getStatus().name() : null,
                    r.getSubmittedAt(),
                    r.getReviewedAt(),
                    r.getCompletedWork(),
                    r.getBlockers(),
                    docId,
                    doc != null ? doc.getFileName() : null,
                    doc != null ? doc.getMimeType() : null));
        }
        return new PerProjectDtos.WeeklyReportsResponse(lc.getUserId(), items);
    }

    // ── §5 Schedule a session for an existing POST_PROJECT evaluation ───

    @Transactional
    public InternEvaluation schedulePostProject(
            UUID evaluationId,
            PerProjectDtos.SchedulePostProjectRequest req,
            User caller) {
        requireEvaluatorOrSuperAdmin(caller);
        if (req == null || req.scheduledFor() == null) {
            throw new BadRequestException("scheduledFor required");
        }
        if (req.scheduledFor().isBefore(Instant.now())) {
            throw new BadRequestException(
                    "scheduledFor must be in the future");
        }
        int duration = req.durationMinutes() != null ? req.durationMinutes() : 45;
        if (duration < 15 || duration > 180) {
            throw new BadRequestException("durationMinutes must be 15-180");
        }
        InternEvaluation ev = evalRepo.findById(evaluationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + evaluationId));
        if (!"POST_PROJECT".equals(ev.getEvaluationType())) {
            throw new BadRequestException(
                    "This endpoint schedules POST_PROJECT evaluations only "
                            + "(row is " + ev.getEvaluationType() + ")");
        }
        // Row-level ownership: pull lifecycle + guard.
        InternLifecycle lc = lifecycleRepo.findById(ev.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found"));
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);

        if (!"DRAFT".equals(ev.getStatus()) && !"SCHEDULED".equals(ev.getStatus())) {
            throw new ConflictException(
                    "Scheduling requires DRAFT or SCHEDULED status (current: "
                            + ev.getStatus() + ")");
        }

        // Stamp evaluator_id on the row if it was auto-drafted under a
        // different (or null) evaluator — the caller is claiming ownership
        // by scheduling. Guarded by scope check above.
        if (ev.getEvaluatorId() == null || !ev.getEvaluatorId().equals(caller.getId())) {
            ev.setEvaluatorId(caller.getId());
        }

        ev.setScheduledFor(req.scheduledFor());
        ev.setDurationMinutes(duration);
        ev.setTimezone(req.timezone() != null && !req.timezone().isBlank()
                ? req.timezone() : "UTC");
        ev.setStatus("SCHEDULED");
        // Stamp the period on POST_PROJECT rows so the recording gallery
        // groups the session under the correct month folder. MONTHLY +
        // FINAL evaluations set period_start at creation; POST_PROJECT
        // rows were auto-drafted without it, then reached the gallery
        // labelled "unknown". scheduledFor is the best signal we have —
        // it's the month the review session actually happens in. Only
        // stamp when null so a re-schedule doesn't rewrite an already-
        // set period.
        if (ev.getPeriodStart() == null) {
            LocalDate scheduledDate = req.scheduledFor()
                    .atZone(java.time.ZoneOffset.UTC).toLocalDate();
            ev.setPeriodStart(scheduledDate);
            ev.setPeriodEnd(scheduledDate);
        }

        // Best-effort Zoom — a failure leaves the row SCHEDULED without a
        // join URL; the evaluator can regenerate later.
        if (meetingProvider.isReady()) {
            try {
                String projectTitle = projectRepository.findById(ev.getLinkedProjectId())
                        .map(p -> p.getTitle() != null ? p.getTitle() : p.getName())
                        .orElse("project");
                String topic = req.topic() != null && !req.topic().isBlank()
                        ? req.topic()
                        : "Project Evaluation — " + projectTitle;
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
                log.warn("[PerProject] Zoom create failed (degraded): {}", e.getMessage());
            }
        }
        InternEvaluation saved = evalRepo.save(ev);
        try {
            fanout.evaluationScheduled(saved, lc, caller);
        } catch (Exception e) {
            log.warn("[PerProject] fanout failed (non-fatal): {}", e.getMessage());
        }
        log.info("[PerProject] scheduled POST_PROJECT eval={} project={} for {} by {}",
                saved.getId(), saved.getLinkedProjectId(), saved.getScheduledFor(), caller.getId());
        return saved;
    }

    /**
     * Project-first scheduling — resolves the POST_PROJECT evaluation
     * for {@code projectId}, auto-creating a DRAFT row when none exists
     * yet (i.e. project hasn't been marked COMPLETED, so the
     * {@code PostProjectEvaluationListener} hasn't fired). Then delegates
     * to {@link #schedulePostProject} for the actual scheduling.
     *
     * <p>Lets the evaluator schedule a session for ANY project on the
     * intern's roster from the per-project card, not just projects that
     * have already been auto-drafted. Ownership is enforced via
     * {@link EvaluatorScopeGuard} against the project's owning lifecycle.</p>
     */
    @Transactional
    public InternEvaluation scheduleByProject(
            UUID projectId,
            PerProjectDtos.SchedulePostProjectRequest req,
            User caller) {
        requireEvaluatorOrSuperAdmin(caller);
        if (req == null || req.scheduledFor() == null) {
            throw new BadRequestException("scheduledFor required");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        if (project.getInternLifecycleId() == null) {
            throw new BadRequestException(
                    "Project is not linked to an intern lifecycle — cannot schedule");
        }
        InternLifecycle lc = lifecycleRepo.findById(project.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + project.getInternLifecycleId()));
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);

        // Find existing POST_PROJECT eval for this project; create DRAFT
        // if none. Only one POST_PROJECT eval per project by convention
        // (repository has findByLinkedProjectId returning Optional).
        InternEvaluation ev = evalRepo.findByLinkedProjectId(projectId).orElse(null);
        if (ev == null) {
            ev = InternEvaluation.builder()
                    .internLifecycleId(lc.getId())
                    .internId(lc.getUserId())
                    .evaluatorId(caller.getId())
                    .evaluationType("POST_PROJECT")
                    .linkedProjectId(projectId)
                    .status("DRAFT")
                    .version(1)
                    .build();
            ev = evalRepo.save(ev);
            log.info("[PerProject] auto-drafted POST_PROJECT eval for project={} by {} "
                    + "(project status={})", projectId, caller.getId(),
                    project.getStatus() != null ? project.getStatus().name() : "?");
        } else if (!"POST_PROJECT".equals(ev.getEvaluationType())) {
            throw new BadRequestException(
                    "Existing evaluation for this project is not POST_PROJECT "
                            + "(is " + ev.getEvaluationType() + ")");
        }
        // Delegate to the eval-first scheduler, which re-runs the scope
        // check + Zoom creation + status transition + fanout.
        return schedulePostProject(ev.getId(), req, caller);
    }

    // ── §4 Bulk / Final session ──────────────────────────────────────────

    /**
     * Schedule ONE session covering MULTIPLE POST_PROJECT evaluations.
     * All rows validated up-front — any failure aborts the batch. On
     * success, one shared Zoom meeting is created and stamped on every
     * evaluation, all rows advance to SCHEDULED (or IN_PROGRESS if
     * {@code markConducted=true}). Notification fan-out fires per row
     * best-effort.
     */
    @Transactional
    public PerProjectDtos.BulkScheduleResponse bulkSchedule(
            PerProjectDtos.BulkScheduleRequest req, User caller) {
        requireEvaluatorOrSuperAdmin(caller);
        if (req == null || req.evaluationIds() == null || req.evaluationIds().isEmpty()) {
            throw new BadRequestException("evaluationIds required");
        }
        if (req.scheduledFor() == null) {
            throw new BadRequestException("scheduledFor required");
        }
        boolean markConducted = Boolean.TRUE.equals(req.markConducted());
        if (!markConducted
                && req.scheduledFor().isBefore(Instant.now())) {
            throw new BadRequestException(
                    "scheduledFor must be in the future "
                            + "(unless markConducted=true to backdate)");
        }
        int duration = req.durationMinutes() != null ? req.durationMinutes() : 45;
        if (duration < 15 || duration > 180) {
            throw new BadRequestException("durationMinutes must be 15-180");
        }
        String timezone = req.timezone() != null && !req.timezone().isBlank()
                ? req.timezone() : "UTC";
        String targetStatus = markConducted ? "IN_PROGRESS" : "SCHEDULED";

        // Deduplicate + load all evaluations, validate each. Load lifecycles
        // in one pass so scope guard doesn't N+1 the DB.
        java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>(req.evaluationIds());
        java.util.List<InternEvaluation> evals = evalRepo.findAllById(ids);
        if (evals.size() != ids.size()) {
            throw new BadRequestException(
                    "One or more evaluations not found (requested " + ids.size()
                            + ", loaded " + evals.size() + ")");
        }
        java.util.Set<UUID> lifecycleIds = new java.util.HashSet<>();
        for (InternEvaluation ev : evals) lifecycleIds.add(ev.getInternLifecycleId());
        java.util.Map<UUID, InternLifecycle> lifecycleMap = new java.util.HashMap<>();
        for (InternLifecycle lc : lifecycleRepo.findAllById(lifecycleIds)) {
            lifecycleMap.put(lc.getId(), lc);
        }
        for (InternEvaluation ev : evals) {
            if (!"POST_PROJECT".equals(ev.getEvaluationType())) {
                throw new BadRequestException(
                        "All rows must be POST_PROJECT (eval " + ev.getId()
                                + " is " + ev.getEvaluationType() + ")");
            }
            if (!"DRAFT".equals(ev.getStatus()) && !"SCHEDULED".equals(ev.getStatus())) {
                throw new ConflictException(
                        "Bulk-schedule requires DRAFT or SCHEDULED (eval "
                                + ev.getId() + " is " + ev.getStatus() + ")");
            }
            InternLifecycle lc = lifecycleMap.get(ev.getInternLifecycleId());
            evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);
        }

        // One shared Zoom meeting for all rows.
        String zoomId = null, joinUrl = null, startUrl = null, password = null;
        if (meetingProvider.isReady()) {
            try {
                String topic = req.topic() != null && !req.topic().isBlank()
                        ? req.topic()
                        : "Final Session — " + evals.size() + " project evaluations";
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(
                                caller.getZoomEmail() != null && !caller.getZoomEmail().isBlank()
                                        ? caller.getZoomEmail() : "me",
                                topic, req.scheduledFor(), duration,
                                timezone, req.agenda()));
                zoomId = z.providerMeetingId();
                joinUrl = z.joinUrl();
                startUrl = z.startUrl();
                password = z.password();
            } catch (Exception e) {
                log.warn("[PerProject] bulk-schedule Zoom create failed (degraded): {}",
                        e.getMessage());
            }
        }

        // Stamp shared meeting details on every row + advance status.
        java.util.List<UUID> updatedIds = new java.util.ArrayList<>(evals.size());
        for (InternEvaluation ev : evals) {
            if (ev.getEvaluatorId() == null || !ev.getEvaluatorId().equals(caller.getId())) {
                ev.setEvaluatorId(caller.getId());
            }
            ev.setScheduledFor(req.scheduledFor());
            ev.setDurationMinutes(duration);
            ev.setTimezone(timezone);
            if (zoomId != null) {
                ev.setZoomMeetingId(zoomId);
                ev.setZoomJoinUrl(joinUrl);
                ev.setZoomStartUrl(startUrl);
                ev.setZoomPassword(password);
            }
            ev.setStatus(targetStatus);
            InternEvaluation saved = evalRepo.save(ev);
            updatedIds.add(saved.getId());
            try {
                InternLifecycle lc = lifecycleMap.get(saved.getInternLifecycleId());
                if (lc != null) fanout.evaluationScheduled(saved, lc, caller);
            } catch (Exception e) {
                log.warn("[PerProject] bulk-schedule fanout failed (non-fatal) eval={}: {}",
                        saved.getId(), e.getMessage());
            }
        }
        log.info("[PerProject] bulk-scheduled {} POST_PROJECT evals under 1 session by {} (status={})",
                updatedIds.size(), caller.getId(), targetStatus);
        return new PerProjectDtos.BulkScheduleResponse(
                updatedIds.size(), zoomId, joinUrl, updatedIds);
    }

    // ── Guards ──────────────────────────────────────────────────────────

    private static void requireEvaluatorOrSuperAdmin(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null) throw new ForbiddenException("No role");
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (caller.getRoles().contains(UserRole.EVALUATOR)) return;
        throw new ForbiddenException("EVALUATOR or SUPER_ADMIN required");
    }
}
