package com.anvicorp.api.trainer.reviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.ExceptionRecord;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.ProjectAssignmentEventLog;
import com.anvicorp.api.entity.ProjectSubmission;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.exception.ExceptionSeverity;
import com.anvicorp.api.erm.exception.ExceptionType;
import com.anvicorp.api.event.ExceptionOpenedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.ExceptionRecordRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectAssignmentEventLogRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.ProjectSubmissionRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.trainer.TrainerScopeGuard;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.AttachmentRef;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.PendingPage;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.PendingRow;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.PriorRound;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.SubmissionDetail;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.SubmitFeedbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trainer Phase 3 — Pending Reviews queue + the doc §9 4-decision
 * Feedback Form workflow.
 *
 * <p>Decision semantics:</p>
 * <ul>
 *   <li>{@code ACCEPT} → project.status=PENDING_VIVA (auto-routed to the
 *       evaluator for the Q&amp;A session + final approval — the trainer
 *       APPROVES, the evaluator COMPLETES), fires FEEDBACK_PUBLISHED.</li>
 *   <li>{@code REQUEST_REVISION} → project.status=RETURNED, intern sees
 *       {@code trainerFeedback} verbatim, fires FEEDBACK_PUBLISHED.</li>
 *   <li>{@code ESCALATE} → upserts a TRAINER_ESCALATION
 *       {@link ExceptionRecord} (severity URGENT) keyed on
 *       {@code (subject_user_id, exception_type)} so the same intern
 *       under continued escalation refreshes one row rather than
 *       creating duplicates. Project status stays SUBMITTED so the
 *       intern can see it's flagged.</li>
 *   <li>{@code NO_ACTION_YET} → silent state, no project status change,
 *       row stays in the Pending queue for the trainer to revisit.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerProjectReviewService {

    private static final int MIN_REVIEW_NOTES = 20;
    private static final int MIN_ESCALATION_REASON = 50;
    private static final int MAX_NOTES = 5_000;
    private static final int MAX_BLOCKERS = 2_000;
    private static final Set<String> DECISIONS =
            Set.of("ACCEPT", "REQUEST_REVISION", "ESCALATE", "NO_ACTION_YET");
    private static final Set<String> NEXT_ACTIONS = Set.of(
            "REVISION", "NEXT_PROJECT", "EXTRA_TRAINING", "ESCALATION", "NONE");

    private final ProjectSubmissionRepository submissionRepository;
    private final ProjectRepository projectRepository;
    private final com.anvicorp.api.repository.ProjectAssignmentRepository
            projectAssignmentRepository;
    private final ProjectAssignmentEventLogRepository eventLogRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final DocumentRepository documentRepository;
    private final TrainerFeedbackNotificationDispatcher notifier;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TrainerScopeGuard trainerScopeGuard;

    // ── List + read ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PendingPage listPending(UUID internLifecycleId, String search,
                                     int page, int pageSize, User caller) {
        requireTrainer(caller);
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));

        StringBuilder where = new StringBuilder(" WHERE s.trainer_decision IS NULL ");
        List<Object> params = new ArrayList<>();
        if (internLifecycleId != null) {
            where.append(" AND p.intern_lifecycle_id = ? ");
            params.add(internLifecycleId);
        }
        // Org-wide trainer model: TRAINER + SUPER_ADMIN both see the
        // shared review queue. Previous `il.trainer_id = caller.id`
        // fence excluded interns whose trainer_id was never stamped
        // (the default under the single-trainer-org config), making the
        // queue invisibly empty. requireTrainer(caller) above already
        // gates the role.
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(p.title) LIKE ?) ");
            String s = "%" + search.trim().toLowerCase() + "%";
            params.add(s); params.add(s);
        }
        String base = " FROM project_submissions s "
                + " JOIN projects p ON p.id = s.project_id "
                + " JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                + " JOIN users u ON u.id = il.user_id "
                + where;
        long total;
        try {
            Long v = jdbc.queryForObject("SELECT COUNT(*) " + base,
                    Long.class, params.toArray());
            total = v == null ? 0L : v;
        } catch (Exception e) {
            log.warn("[TrainerReview] count failed: {}", e.getMessage());
            total = 0L;
        }
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        String select = "SELECT s.id AS submission_id, s.project_id, "
                + "       p.intern_lifecycle_id, il.user_id AS intern_user_id, "
                + "       u.full_name AS intern_name, p.title AS project_title, "
                + "       p.tech_stack AS technology_area, s.submitted_at, "
                + "       s.version AS version, p.month_year, p.project_number, "
                + "       p.due_date, s.description, s.links_json "
                + base + " ORDER BY s.submitted_at ASC LIMIT ? OFFSET ?";
        List<PendingRow> rows = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(select, pageParams.toArray())) {
                Instant submitted = toInstant(r.get("submitted_at"));
                long hoursWaiting = submitted == null ? 0
                        : Duration.between(submitted, Instant.now()).toHours();
                rows.add(new PendingRow(
                        uuid(r.get("submission_id")),
                        uuid(r.get("project_id")),
                        uuid(r.get("intern_lifecycle_id")),
                        uuid(r.get("intern_user_id")),
                        (String) r.get("intern_name"),
                        (String) r.get("project_title"),
                        (String) r.get("technology_area"),
                        submitted, hoursWaiting,
                        intVal(r.get("version")),
                        (String) r.get("month_year"),
                        shortVal(r.get("project_number")),
                        toLocalDate(r.get("due_date")),
                        (String) r.get("description"),
                        (String) r.get("links_json")));
            }
        } catch (Exception e) {
            log.warn("[TrainerReview] page query failed: {}", e.getMessage());
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new PendingPage(rows, p, ps, total, totalPages);
    }

    @Transactional(readOnly = true)
    public SubmissionDetail getDetail(UUID submissionId, User caller) {
        requireTrainer(caller);
        ProjectSubmission s = mustLoad(submissionId);
        Project p = mustLoadProject(s.getProject().getId());
        requireProjectInScope(p, caller);
        InternLifecycle lc = p.getInternLifecycleId() != null
                ? lifecycleRepository.findById(p.getInternLifecycleId()).orElse(null)
                : null;
        User intern = lc != null && lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;

        List<PriorRound> prior = new ArrayList<>();
        for (ProjectSubmission other : submissionRepository
                .findByProjectIdOrderBySubmittedAtDesc(p.getId())) {
            if (other.getId().equals(s.getId())) continue;
            prior.add(new PriorRound(
                    other.getId(), other.getSubmittedAt(),
                    other.getVersion() == null ? 1 : other.getVersion(),
                    other.getDescription(),
                    other.getTrainerDecision(), other.getTrainerFeedback(),
                    other.getReviewedAt()));
        }
        prior.sort(Comparator.comparing(PriorRound::version).reversed());

        AttachmentRef attachment = null;
        if (s.getAttachmentDocumentId() != null) {
            Document doc = documentRepository.findById(s.getAttachmentDocumentId()).orElse(null);
            if (doc != null && doc.getDeletedAt() == null) {
                attachment = new AttachmentRef(
                        doc.getId(), doc.getFileName(),
                        doc.getMimeType(), doc.getFileSize());
            }
        }

        return new SubmissionDetail(
                s.getId(), p.getId(), p.getInternLifecycleId(),
                lc != null ? lc.getUserId() : null,
                intern != null ? intern.getFullName() : null,
                p.getTitle(), p.getTechStack(), p.getInstructions(),
                s.getSubmittedAt(),
                s.getVersion() == null ? 1 : s.getVersion(),
                s.getDescription(), s.getLinksJson(),
                prior,
                s.getTechnicalScore(), s.getCommunicationScore(),
                s.getBlockersNote(), s.getNextAction(),
                s.getNextActionDueDate(), s.getReviewedLinksCsv(),
                s.getTrainerDecision(), s.getTrainerFeedback(),
                s.getCompletionStatus(), s.getReviewedAt(),
                attachment);
    }

    // ── Submit feedback (the workhorse) ──────────────────────────────────

    @Transactional
    public SubmissionDetail submitFeedback(UUID submissionId,
                                            SubmitFeedbackRequest req,
                                            User caller) {
        requireTrainer(caller);
        if (req == null) throw new BadRequestException("body required");
        String decision = req.decision() == null ? ""
                : req.decision().trim().toUpperCase();
        if (!DECISIONS.contains(decision)) {
            throw new BadRequestException(
                    "decision must be one of " + DECISIONS);
        }
        ProjectSubmission s = mustLoad(submissionId);
        Project p = mustLoadProject(s.getProject().getId());
        requireProjectInScope(p, caller);
        if (s.getTrainerDecision() != null
                && !"NO_ACTION_YET".equals(s.getTrainerDecision())) {
            throw new ConflictException(
                    "Submission already has decision " + s.getTrainerDecision());
        }

        validateScoresAndLengths(req, decision);
        validateNextAction(req.nextAction());

        // ── Persist the doc Feedback Form fields ─────────────────────────
        if (req.technicalScore() != null) s.setTechnicalScore(req.technicalScore());
        if (req.communicationScore() != null) s.setCommunicationScore(req.communicationScore());
        if (req.blockersNote() != null) s.setBlockersNote(req.blockersNote().trim());
        if (req.trainerFeedback() != null) s.setTrainerFeedback(req.trainerFeedback().trim());
        if (req.nextAction() != null) s.setNextAction(req.nextAction().trim());
        if (req.nextActionDueDate() != null) s.setNextActionDueDate(req.nextActionDueDate());
        if (req.reviewedLinksCsv() != null) s.setReviewedLinksCsv(req.reviewedLinksCsv().trim());
        if (req.completionStatus() != null) s.setCompletionStatus(req.completionStatus().trim());
        s.setTrainerDecision(decision);
        s.setReviewedAt(Instant.now());
        s.setReviewedById(caller.getId());
        s = submissionRepository.save(s);

        // ── Project-level side effects ───────────────────────────────────
        String prevStatus = p.getStatus() != null ? p.getStatus().name() : null;
        switch (decision) {
            case "ACCEPT" -> {
                // Trainer "Review & approve" routes the project to the
                // evaluator's queue for the Q&A session + final approval.
                // The trainer APPROVES; the evaluator COMPLETES (via
                // QaSessionService.signOff → ProjectWorkflowService
                // .completeAfterViva). completedAt is intentionally NOT
                // stamped here — only the evaluator's final sign-off
                // sets it.
                p.setStatus(ProjectStatus.PENDING_VIVA);
                p.setReviewNotes(s.getTrainerFeedback());
                p.setReviewedBy(caller);
                p.setReviewedAt(Instant.now());
                projectRepository.save(p);
                // Mirror the decision into the project_assignments row
                // the intern reads from /api/v1/project-assignments/mine.
                // Without this mirror, ProjectAssignment.status stayed
                // SUBMITTED while Project.status was PENDING_VIVA — the
                // intern's tracker + status pills disagreed with the
                // workflow (showed "Trainer review" forever even though
                // the trainer had already approved).
                mirrorAssignmentStatus(p.getId(),
                        com.anvicorp.api.enums.ProjectAssignmentStatus.PENDING_VIVA);
            }
            case "REQUEST_REVISION" -> {
                p.setStatus(ProjectStatus.RETURNED);
                p.setReviewNotes(s.getTrainerFeedback());
                p.setReviewedBy(caller);
                p.setReviewedAt(Instant.now());
                if (req.nextActionDueDate() != null) {
                    p.setDueDate(req.nextActionDueDate());
                } else if (p.getDueDate() != null) {
                    p.setDueDate(p.getDueDate().plusDays(7));
                }
                projectRepository.save(p);
                mirrorAssignmentStatus(p.getId(),
                        com.anvicorp.api.enums.ProjectAssignmentStatus.RETURNED);
            }
            case "ESCALATE" -> {
                // Project status stays SUBMITTED so it's visible to the
                // intern as flagged. ExceptionRecord upsert below routes
                // the issue to ERM + Manager via the Phase 6 surface.
                upsertTrainerEscalation(p, caller, req.escalationReason());
            }
            case "NO_ACTION_YET" -> {
                // Silent — no project status change, no notification.
            }
            default -> throw new BadRequestException("unknown decision: " + decision);
        }

        // ── Event log + audit ────────────────────────────────────────────
        Map<String, Object> decisionPayload = new LinkedHashMap<>();
        decisionPayload.put("submissionId", s.getId());
        decisionPayload.put("version", s.getVersion());
        decisionPayload.put("decision", decision);
        decisionPayload.put("completionStatus", s.getCompletionStatus());
        decisionPayload.put("technicalScore", s.getTechnicalScore());
        decisionPayload.put("communicationScore", s.getCommunicationScore());
        appendEventLog(p.getId(), caller.getId(),
                decisionToEventType(decision), s, decisionPayload);

        Map<String, Object> publishPayload = new LinkedHashMap<>();
        publishPayload.put("submissionId", s.getId());
        publishPayload.put("decision", decision);
        appendEventLog(p.getId(), caller.getId(), "FEEDBACK_PUBLISHED",
                "trainer feedback published", publishPayload);

        Map<String, Object> auditBefore = new LinkedHashMap<>();
        auditBefore.put("previousStatus", prevStatus);
        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("decision", decision);
        auditAfter.put("version", s.getVersion());
        UUID subjectUserId = p.getInternLifecycleId() != null
                ? lifecycleRepository.findById(p.getInternLifecycleId())
                    .map(InternLifecycle::getUserId).orElse(null)
                : null;
        writeAudit(p.getId(), "TRAINER_FEEDBACK_" + decision,
                caller.getId(), subjectUserId, auditBefore, auditAfter);

        // ── Notification fan-out (skip on NO_ACTION_YET) ─────────────────
        if (!"NO_ACTION_YET".equals(decision)) {
            try {
                notifier.dispatchFeedbackPublished(p, s, caller, decision);
            } catch (Exception e) {
                log.warn("[TrainerReview] feedback dispatch failed: {}",
                        e.getMessage());
            }
        }
        return getDetail(submissionId, caller);
    }

    // ── Mirror trainer decision → ProjectAssignment.status ───────────────

    /**
     * Mirror the trainer's decision into every active ProjectAssignment
     * row for this project so the intern's
     * {@code /api/v1/project-assignments/mine} response stays in sync
     * with the workflow state on {@code Project.status}. Without the
     * mirror, the intern's tracker + status pills disagree with the
     * actual project state (e.g. project moved to PENDING_VIVA but the
     * assignment row still says SUBMITTED).
     *
     * <p>Idempotent + best-effort: skips rows already in the target
     * status; a write failure logs at WARN and never breaks the
     * trainer's submit-feedback call.</p>
     */
    private void mirrorAssignmentStatus(
            UUID projectId,
            com.anvicorp.api.enums.ProjectAssignmentStatus target) {
        if (projectId == null || target == null) return;
        try {
            var rows = projectAssignmentRepository
                    .findByProjectIdOrderByAssignmentDateDescCreatedAtDesc(projectId);
            for (var a : rows) {
                if (a.getStatus() == target) continue;
                // COMPLETED is a terminal state on the assignment row —
                // never roll it back via a trainer-decision mirror.
                if (a.getStatus()
                        == com.anvicorp.api.enums.ProjectAssignmentStatus.COMPLETED) {
                    continue;
                }
                a.setStatus(target);
                projectAssignmentRepository.save(a);
            }
        } catch (Exception e) {
            log.warn("[TrainerReview] mirror assignment status to {} failed "
                    + "(non-fatal) for project {}: {}",
                    target, projectId, e.getMessage());
        }
    }

    // ── ESCALATE: TRAINER_ESCALATION ExceptionRecord upsert ──────────────

    private void upsertTrainerEscalation(Project project, User trainer,
                                          String escalationReason) {
        InternLifecycle lc = project.getInternLifecycleId() != null
                ? lifecycleRepository.findById(project.getInternLifecycleId())
                        .orElse(null) : null;
        if (lc == null || lc.getUserId() == null) {
            log.warn("[TrainerReview] cannot upsert escalation — no lifecycle/user");
            return;
        }
        Instant now = Instant.now();
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("projectId", project.getId());
        payloadMap.put("projectTitle", project.getTitle());
        payloadMap.put("escalatedByTrainerId", trainer.getId());
        payloadMap.put("escalationReason", escalationReason);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadMap);
        } catch (Exception e) {
            payload = null;
        }
        var existing = exceptionRepository.findActiveBySubjectAndType(
                lc.getUserId(), ExceptionType.TRAINER_ESCALATION.name());
        if (existing.isPresent()) {
            ExceptionRecord rec = existing.get();
            rec.setLastSeenAt(now);
            rec.setPayloadJson(payload);
            rec.setSubjectResourceType("PROJECT");
            rec.setSubjectResourceId(project.getId());
            exceptionRepository.save(rec);
            log.info("[TrainerReview] refreshed TRAINER_ESCALATION {} for user {}",
                    rec.getId(), lc.getUserId());
            return;
        }
        ExceptionRecord rec = ExceptionRecord.builder()
                .internLifecycleId(lc.getId())
                .subjectUserId(lc.getUserId())
                .exceptionType(ExceptionType.TRAINER_ESCALATION.name())
                .severity(ExceptionSeverity.URGENT.name())
                .status("OPEN")
                .openedAt(now)
                .lastSeenAt(now)
                .subjectResourceType("PROJECT")
                .subjectResourceId(project.getId())
                .payloadJson(payload)
                .build();
        ExceptionRecord saved = exceptionRepository.save(rec);
        try {
            eventPublisher.publishEvent(new ExceptionOpenedEvent(
                    saved.getId(), saved.getSubjectUserId(),
                    saved.getInternLifecycleId(),
                    saved.getExceptionType(), saved.getSeverity()));
        } catch (Exception e) {
            log.debug("[TrainerReview] escalation event publish failed: {}",
                    e.getMessage());
        }
        log.info("[TrainerReview] opened TRAINER_ESCALATION {} for user {}",
                saved.getId(), lc.getUserId());
    }

    // ── Validation helpers ───────────────────────────────────────────────

    private void validateScoresAndLengths(SubmitFeedbackRequest req, String decision) {
        if (req.technicalScore() != null
                && (req.technicalScore() < 1 || req.technicalScore() > 5)) {
            throw new BadRequestException("technicalScore must be 1-5");
        }
        if (req.communicationScore() != null
                && (req.communicationScore() < 1 || req.communicationScore() > 5)) {
            throw new BadRequestException("communicationScore must be 1-5");
        }
        if (req.trainerFeedback() != null
                && req.trainerFeedback().length() > MAX_NOTES) {
            throw new BadRequestException("trainerFeedback exceeds " + MAX_NOTES + " chars");
        }
        if (req.blockersNote() != null
                && req.blockersNote().length() > MAX_BLOCKERS) {
            throw new BadRequestException("blockersNote exceeds " + MAX_BLOCKERS + " chars");
        }
        switch (decision) {
            case "ACCEPT", "REQUEST_REVISION" -> {
                if (req.technicalScore() == null || req.communicationScore() == null) {
                    throw new BadRequestException(
                            "technicalScore + communicationScore required for "
                                    + decision);
                }
                if (req.trainerFeedback() == null
                        || req.trainerFeedback().trim().length() < MIN_REVIEW_NOTES) {
                    throw new BadRequestException(
                            "trainerFeedback must be at least "
                                    + MIN_REVIEW_NOTES + " chars for " + decision);
                }
            }
            case "ESCALATE" -> {
                if (req.escalationReason() == null
                        || req.escalationReason().trim().length() < MIN_ESCALATION_REASON) {
                    throw new BadRequestException(
                            "escalationReason must be at least "
                                    + MIN_ESCALATION_REASON + " chars for ESCALATE");
                }
            }
            case "NO_ACTION_YET" -> {
                // no requirements
            }
            default -> {}
        }
    }

    private void validateNextAction(String nextAction) {
        if (nextAction == null || nextAction.isBlank()) return;
        if (!NEXT_ACTIONS.contains(nextAction.trim().toUpperCase())) {
            throw new BadRequestException(
                    "nextAction must be one of " + NEXT_ACTIONS);
        }
    }

    private static String decisionToEventType(String d) {
        return switch (d) {
            // ACCEPT routes to PENDING_VIVA (evaluator Q&A), not COMPLETED —
            // the evaluator's final approval emits COMPLETED downstream.
            case "ACCEPT" -> "TRAINER_APPROVED_PENDING_VIVA";
            case "REQUEST_REVISION" -> "REVISION_REQUESTED";
            case "ESCALATE" -> "ESCALATED";
            default -> "REVIEWED";
        };
    }

    // ── Logging helpers ──────────────────────────────────────────────────

    private void appendEventLog(UUID projectId, UUID actorId, String eventType,
                                 Object commentsObj, Map<String, Object> payload) {
        try {
            String payloadJson = payload != null && !payload.isEmpty()
                    ? objectMapper.writeValueAsString(payload) : null;
            String comments = commentsObj == null ? null
                    : commentsObj instanceof String str ? str : String.valueOf(commentsObj);
            ProjectAssignmentEventLog row = ProjectAssignmentEventLog.builder()
                    .projectId(projectId)
                    .actorUserId(actorId)
                    .eventType(eventType)
                    .comments(comments)
                    .payloadJson(payloadJson)
                    .build();
            eventLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[TrainerReview] event log {} failed: {}", eventType, e.getMessage());
        }
    }

    private void writeAudit(UUID entityId, String action, UUID actorId,
                             UUID subjectUserId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType("Project")
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null
                            ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null
                            ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[TrainerReview] audit write failed: {}", e.getMessage());
        }
    }

    // ── Common guards ────────────────────────────────────────────────────

    private void requireTrainer(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.TRAINER)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
    }

    private void requireProjectInScope(Project p, User caller) {
        // Legacy single-allocation rows have no lifecycle binding; preserve
        // the assigned_by fallback for those (no guard applies). For
        // catalog rows bound to a lifecycle, delegate to the shared
        // TrainerScopeGuard so the queue / accept / return / escalate
        // flows inherit the single-trainer null-fallback that KT and
        // project-assign already use.
        if (p.getInternLifecycleId() == null) {
            if (caller.getRoles() != null
                    && caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
            if (p.getAssignedBy() != null
                    && caller.getId().equals(p.getAssignedBy().getId())) return;
            throw new ForbiddenException("Project is not in your scope");
        }
        InternLifecycle lc = lifecycleRepository.findById(p.getInternLifecycleId())
                .orElse(null);
        trainerScopeGuard.requireTrainerOwnership(lc, caller);
    }

    private ProjectSubmission mustLoad(UUID id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Submission not found: " + id));
    }

    private Project mustLoadProject(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + id));
    }

    // ── Primitive helpers ────────────────────────────────────────────────

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static int intVal(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); }
        catch (Exception e) { return 0; }
    }

    private static Short shortVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.shortValue();
        try { return Short.parseShort(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        return null;
    }

    private static java.time.LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
