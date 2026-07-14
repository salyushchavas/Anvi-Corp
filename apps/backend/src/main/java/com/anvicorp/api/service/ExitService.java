package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.exit.ExitDtos;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.ExitFeedback;
import com.anvicorp.api.entity.ExitRecord;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.ProjectAssignment;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.ProjectAssignmentStatus;
import com.anvicorp.api.enums.TimesheetStatus;
import com.anvicorp.api.event.ExitFeedbackSubmittedEvent;
import com.anvicorp.api.event.ExitInitiatedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.InternLifecycleService;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.ExitFeedbackRepository;
import com.anvicorp.api.repository.ExitRecordRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 8 — exit lifecycle service.
 *
 * <p>Owns ExitRecord creation, amendment within the 7-day window,
 * checklist updates (GitHub revocation + documents archived), final
 * evaluation linking, one-time feedback submission, and the read-side
 * surfaces (records, pending list, intern summary). Atomically:</p>
 *
 * <ol>
 *   <li>Persists the {@link ExitRecord}.</li>
 *   <li>Flips {@code intern_lifecycles.active_status} +
 *       {@code ended_at}.</li>
 *   <li>Advances {@code users.lifecycle_status} to {@code INACTIVE_INTERN}
 *       via the existing {@link InternLifecycleService}.</li>
 *   <li>Publishes {@link ExitInitiatedEvent} for the GitHub revocation
 *       listener + Phase 7 notification fan-out.</li>
 *   <li>Writes audit rows: {@code EXIT_INITIATED} and
 *       {@code LIFECYCLE_STATUS_CHANGED}.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExitService {

    public static final Set<String> EXIT_TYPES = Set.of(
            "COMPLETED", "RESIGNED", "TERMINATED", "EXTENDED");

    public static final Set<String> EXIT_REASON_REQUIRED = Set.of("RESIGNED", "TERMINATED");

    /** ERM amendment window after creation. */
    private static final int AMENDMENT_WINDOW_DAYS = 7;
    private static final int FUTURE_EXIT_LIMIT_DAYS = 90;
    private static final int EXIT_REASON_MIN = 30;
    private static final int INTERN_VISIBLE_SUMMARY_MAX = 500;
    private static final int FEEDBACK_NARRATIVE_MIN = 50;
    private static final int FEEDBACK_COMMENT_MAX = 2000;

    private final ExitRecordRepository exitRecordRepository;
    private final ExitFeedbackRepository exitFeedbackRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final InternLifecycleService internLifecycleService;
    private final InternEvaluationRepository internEvaluationRepository;
    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final TimesheetRepository timesheetRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    // ERM Phase 7 — auto-seed 8 checklist rows on exit initiation.
    private final com.anvicorp.api.repository.ExitChecklistItemRepository exitChecklistItemRepository;

    // ── Commands ───────────────────────────────────────────────────────────

    @Transactional
    public ExitDtos.ExitRecordResponse initiate(ExitDtos.CreateExitRecordRequest req, User actor) {
        if (req == null) throw new BadRequestException("request body is required");
        String exitType = normalizeType(req.exitType());
        LocalDate exitDate = req.exitDate();
        if (exitDate == null) throw new BadRequestException("exitDate is required");
        if (exitDate.isAfter(LocalDate.now().plusDays(FUTURE_EXIT_LIMIT_DAYS))) {
            throw new BadRequestException(
                    "exitDate cannot be more than " + FUTURE_EXIT_LIMIT_DAYS + " days in the future");
        }
        if (EXIT_REASON_REQUIRED.contains(exitType)) {
            if (req.exitReason() == null || req.exitReason().trim().length() < EXIT_REASON_MIN) {
                throw new BadRequestException(
                        "exitReason is required and must be at least "
                                + EXIT_REASON_MIN + " characters for " + exitType);
            }
        }
        if (req.internVisibleSummary() != null
                && req.internVisibleSummary().length() > INTERN_VISIBLE_SUMMARY_MAX) {
            throw new BadRequestException(
                    "internVisibleSummary cannot exceed " + INTERN_VISIBLE_SUMMARY_MAX + " characters");
        }
        if (req.internLifecycleId() == null) {
            throw new BadRequestException("internLifecycleId is required");
        }

        InternLifecycle lc = internLifecycleRepository.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + req.internLifecycleId()));
        if (!"ACTIVE".equals(lc.getActiveStatus())) {
            throw new ConflictException(
                    "Cannot initiate exit; lifecycle active_status is "
                            + lc.getActiveStatus() + " (expected ACTIVE)");
        }
        if (exitRecordRepository.existsByInternLifecycleId(lc.getId())) {
            throw new ConflictException("An ExitRecord already exists for this lifecycle");
        }

        Instant now = Instant.now();
        ExitRecord record = ExitRecord.builder()
                .internLifecycleId(lc.getId())
                .internId(lc.getUserId())
                .exitType(exitType)
                .exitDate(exitDate)
                .exitReason(trimOrNull(req.exitReason()))
                .initiatedById(actor.getId())
                .rehireEligible(req.rehireEligible() != null ? req.rehireEligible() : Boolean.TRUE)
                .accessRevocationDone(false)
                .finalDocumentsArchived(false)
                .internVisibleSummary(trimOrNull(req.internVisibleSummary()))
                .build();
        record = exitRecordRepository.save(record);

        // ERM Phase 7 — seed the 8 checklist items so the ERM exit detail
        // page has rows to render and downstream listeners have rows to
        // flip. Idempotent — only seeds rows that don't already exist
        // (re-running initiate would error out higher up anyway).
        seedChecklistItems(record.getId());

        // Flip lifecycle state.
        String newActive = mapToLifecycleActiveStatus(exitType);
        String previousActive = lc.getActiveStatus();
        lc.setActiveStatus(newActive);
        lc.setEndedAt(now);
        internLifecycleRepository.save(lc);

        // Advance user.lifecycle_status — monotonic via InternLifecycleService.
        User intern = userRepository.findById(lc.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern user not found: " + lc.getUserId()));
        InternLifecycleStatus previousLifecycle = intern.getLifecycleStatus();
        internLifecycleService.advance(intern, InternLifecycleStatus.INACTIVE_INTERN, actor.getId());

        // Audit rows.
        writeAudit(actor.getId(), lc.getUserId(), "EXIT_INITIATED",
                "InternLifecycle", lc.getId(),
                Map.of("activeStatus", previousActive),
                Map.of("exitType", exitType, "exitDate", exitDate.toString(),
                        "exitRecordId", record.getId().toString(),
                        "activeStatus", newActive));
        writeAudit(actor.getId(), lc.getUserId(), "LIFECYCLE_STATUS_CHANGED",
                "User", lc.getUserId(),
                Map.of("lifecycleStatus", previousLifecycle != null
                        ? previousLifecycle.name() : "UNKNOWN"),
                Map.of("lifecycleStatus", InternLifecycleStatus.INACTIVE_INTERN.name()));

        // Fan-out.
        try {
            eventPublisher.publishEvent(new ExitInitiatedEvent(
                    record.getId(), lc.getId(), lc.getUserId(),
                    actor.getId(), exitType, exitDate));
        } catch (Exception e) {
            log.warn("[Exit] event publish failed (non-fatal) for {}: {}",
                    record.getId(), e.getMessage());
        }

        log.info("[Exit] initiated: record={} intern={} type={} date={}",
                record.getId(), lc.getUserId(), exitType, exitDate);
        return toErmResponse(record);
    }

    @Transactional
    public ExitDtos.ExitRecordResponse amend(UUID recordId,
                                              ExitDtos.PatchExitRecordRequest req,
                                              User actor) {
        ExitRecord record = mustLoad(recordId);
        ensureAmendmentInitiator(record, actor);
        ensureAmendmentWindow(record);
        Map<String, Object> before = snapshotForAudit(record);
        if (req.exitReason() != null) record.setExitReason(trimOrNull(req.exitReason()));
        if (req.internVisibleSummary() != null) {
            if (req.internVisibleSummary().length() > INTERN_VISIBLE_SUMMARY_MAX) {
                throw new BadRequestException(
                        "internVisibleSummary cannot exceed "
                                + INTERN_VISIBLE_SUMMARY_MAX + " characters");
            }
            record.setInternVisibleSummary(trimOrNull(req.internVisibleSummary()));
        }
        if (req.rehireEligible() != null) record.setRehireEligible(req.rehireEligible());
        if (req.internalNotes() != null) record.setInternalNotes(trimOrNull(req.internalNotes()));
        record.setAmendedAt(Instant.now());
        record = exitRecordRepository.save(record);
        writeAudit(actor.getId(), record.getInternId(), "EXIT_RECORD_AMENDED",
                "ExitRecord", record.getId(),
                before, snapshotForAudit(record));
        return toErmResponse(record);
    }

    @Transactional
    public ExitDtos.ExitRecordResponse updateChecklist(UUID recordId, String itemKey,
                                                        ExitDtos.ChecklistRequest req,
                                                        User actor) {
        ExitRecord record = mustLoad(recordId);
        ensureAmendmentInitiator(record, actor);
        boolean done = Boolean.TRUE.equals(req.done());
        switch (itemKey) {
            case "access-revocation" -> {
                record.setAccessRevocationDone(done);
                if (req.summary() != null) {
                    record.setAccessRevocationSummary(trimOrNull(req.summary()));
                }
                if (done && record.getAccessRevocationAttemptedAt() == null) {
                    record.setAccessRevocationAttemptedAt(Instant.now());
                }
            }
            case "documents-archived" -> record.setFinalDocumentsArchived(done);
            default -> throw new BadRequestException(
                    "Unknown checklist itemKey: " + itemKey
                            + " (expected access-revocation or documents-archived)");
        }
        record = exitRecordRepository.save(record);
        writeAudit(actor.getId(), record.getInternId(), "EXIT_CHECKLIST_UPDATED",
                "ExitRecord", record.getId(),
                Map.of("itemKey", itemKey),
                Map.of("done", done, "itemKey", itemKey));
        return toErmResponse(record);
    }

    @Transactional
    public ExitDtos.ExitRecordResponse linkFinalEvaluation(UUID recordId,
                                                            ExitDtos.LinkFinalEvaluationRequest req,
                                                            User actor) {
        ExitRecord record = mustLoad(recordId);
        ensureAmendmentInitiator(record, actor);
        if (req.evaluationId() == null) {
            throw new BadRequestException("evaluationId is required");
        }
        InternEvaluation eval = internEvaluationRepository.findById(req.evaluationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + req.evaluationId()));
        if (!record.getInternId().equals(eval.getInternId())) {
            throw new BadRequestException("Evaluation does not belong to this intern");
        }
        if (!"FINAL".equalsIgnoreCase(eval.getEvaluationType())) {
            throw new BadRequestException(
                    "Evaluation must be type=FINAL (was " + eval.getEvaluationType() + ")");
        }
        if (!"PUBLISHED".equalsIgnoreCase(eval.getStatus())
                && !"ACKNOWLEDGED".equalsIgnoreCase(eval.getStatus())
                && !"AMENDED".equalsIgnoreCase(eval.getStatus())) {
            throw new BadRequestException(
                    "Evaluation must be PUBLISHED (current: " + eval.getStatus() + ")");
        }
        record.setFinalEvaluationId(eval.getId());
        record = exitRecordRepository.save(record);
        writeAudit(actor.getId(), record.getInternId(), "EXIT_FINAL_EVAL_LINKED",
                "ExitRecord", record.getId(),
                null,
                Map.of("evaluationId", eval.getId().toString()));
        return toErmResponse(record);
    }

    /** Re-publishes the ExitInitiatedEvent so the listener retries the revocation pass. */
    @Transactional
    public ExitDtos.ExitRecordResponse retryRevocation(UUID recordId, User actor) {
        ExitRecord record = mustLoad(recordId);
        ensureAmendmentInitiator(record, actor);
        InternLifecycle lc = internLifecycleRepository.findById(record.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lifecycle missing for record " + recordId));
        eventPublisher.publishEvent(new ExitInitiatedEvent(
                record.getId(), lc.getId(), record.getInternId(),
                actor.getId(), record.getExitType(), record.getExitDate()));
        writeAudit(actor.getId(), record.getInternId(), "EXIT_REVOCATION_RETRY",
                "ExitRecord", record.getId(), null, null);
        return toErmResponse(record);
    }

    // ── Feedback ────────────────────────────────────────────────────────────

    @Transactional
    public ExitDtos.ExitFeedbackResponse submitFeedback(ExitDtos.SubmitFeedbackRequest req,
                                                         User intern) {
        ExitRecord record = exitRecordRepository.findByInternId(intern.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ExitRecord for caller — feedback not applicable"));
        if (exitFeedbackRepository.existsByExitRecordId(record.getId())) {
            throw new ConflictException(
                    "Exit feedback already submitted; contact support to amend");
        }
        validateFeedback(req);
        Instant now = Instant.now();
        ExitFeedback fb = ExitFeedback.builder()
                .exitRecordId(record.getId())
                .internId(intern.getId())
                .overallRating(req.overallRating())
                .learningRating(req.learningRating())
                .mentorshipRating(req.mentorshipRating())
                .workEnvironmentRating(req.workEnvironmentRating())
                .whatWentWell(req.whatWentWell().trim())
                .whatCouldImprove(req.whatCouldImprove().trim())
                .wouldRecommend(req.wouldRecommend())
                .additionalComments(trimOrNull(req.additionalComments()))
                .submittedAt(now)
                .build();
        fb = exitFeedbackRepository.save(fb);
        writeAudit(intern.getId(), intern.getId(), "EXIT_FEEDBACK_SUBMITTED",
                "ExitFeedback", fb.getId(),
                null,
                Map.of("exitRecordId", record.getId().toString(),
                        "overallRating", req.overallRating(),
                        "wouldRecommend", req.wouldRecommend()));
        try {
            eventPublisher.publishEvent(new ExitFeedbackSubmittedEvent(
                    fb.getId(), record.getId(), intern.getId()));
        } catch (Exception e) {
            log.warn("[Exit] feedback event publish failed (non-fatal): {}", e.getMessage());
        }
        // ERM Phase 7 — flip the EXIT_FEEDBACK_SUBMITTED checklist item.
        flipChecklistItem(record.getId(),
                com.anvicorp.api.erm.exit.ExitChecklistItemRegistry.EXIT_FEEDBACK_SUBMITTED,
                com.anvicorp.api.erm.exit.ExitChecklistItemRegistry.STATUS_COMPLETE,
                intern.getId(),
                "Auto-completed by intern feedback submission.");
        return toFeedbackResponse(fb);
    }

    private void validateFeedback(ExitDtos.SubmitFeedbackRequest req) {
        if (req == null) throw new BadRequestException("request body is required");
        validateRating("overallRating", req.overallRating());
        validateRating("learningRating", req.learningRating());
        validateRating("mentorshipRating", req.mentorshipRating());
        validateRating("workEnvironmentRating", req.workEnvironmentRating());
        if (req.wouldRecommend() == null) {
            throw new BadRequestException("wouldRecommend is required");
        }
        if (req.whatWentWell() == null
                || req.whatWentWell().trim().length() < FEEDBACK_NARRATIVE_MIN) {
            throw new BadRequestException(
                    "whatWentWell must be at least " + FEEDBACK_NARRATIVE_MIN + " characters");
        }
        if (req.whatCouldImprove() == null
                || req.whatCouldImprove().trim().length() < FEEDBACK_NARRATIVE_MIN) {
            throw new BadRequestException(
                    "whatCouldImprove must be at least " + FEEDBACK_NARRATIVE_MIN + " characters");
        }
        if (req.additionalComments() != null
                && req.additionalComments().length() > FEEDBACK_COMMENT_MAX) {
            throw new BadRequestException(
                    "additionalComments cannot exceed " + FEEDBACK_COMMENT_MAX + " characters");
        }
    }

    private void validateRating(String field, Integer value) {
        if (value == null || value < 1 || value > 5) {
            throw new BadRequestException(field + " must be between 1 and 5");
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ExitDtos.ExitRecordResponse getById(UUID id, User caller) {
        ExitRecord record = mustLoad(id);
        // Owner intern allowed via the intern view path; staff allowed via
        // controller-layer @PreAuthorize. This method returns the full
        // staff projection — controller is responsible for picking which
        // shape to return to interns.
        return toErmResponse(record);
    }

    @Transactional(readOnly = true)
    public ExitDtos.ExitRecordInternView getInternView(UUID id) {
        return toInternView(mustLoad(id));
    }

    @Transactional(readOnly = true)
    public Optional<ExitRecord> findByInternUser(UUID internUserId) {
        return exitRecordRepository.findByInternId(internUserId);
    }

    @Transactional(readOnly = true)
    public ExitDtos.ExitRecordListPage list(String exitType, LocalDate from, LocalDate to,
                                              int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, pageSize), 100));
        Page<ExitRecord> p;
        if (exitType != null && from != null && to != null) {
            p = exitRecordRepository
                    .findByExitTypeAndExitDateBetweenOrderByExitDateDescCreatedAtDesc(
                            normalizeType(exitType), from, to, pageable);
        } else if (exitType != null) {
            p = exitRecordRepository
                    .findByExitTypeOrderByExitDateDescCreatedAtDesc(normalizeType(exitType), pageable);
        } else if (from != null && to != null) {
            p = exitRecordRepository
                    .findByExitDateBetweenOrderByExitDateDescCreatedAtDesc(from, to, pageable);
        } else {
            p = exitRecordRepository.findAllByOrderByExitDateDescCreatedAtDesc(pageable);
        }
        List<ExitDtos.ExitRecordResponse> items = p.getContent().stream()
                .map(this::toErmResponse).toList();
        return new ExitDtos.ExitRecordListPage(items, p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    /**
     * "Ready to exit" — active lifecycles whose readiness signals trip. The
     * signals are pragmatic, not exhaustive: cycle length past 90 days, no
     * exit row yet. ERM uses this as a starting list and decides whether to
     * initiate per intern.
     */
    @Transactional(readOnly = true)
    public List<ExitDtos.PendingExitItem> pending() {
        List<InternLifecycle> all = internLifecycleRepository.findAll();
        List<ExitDtos.PendingExitItem> out = new ArrayList<>();
        Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
        for (InternLifecycle lc : all) {
            if (!"ACTIVE".equals(lc.getActiveStatus())) continue;
            if (exitRecordRepository.existsByInternLifecycleId(lc.getId())) continue;
            List<String> signals = new ArrayList<>();
            if (lc.getHiredAt() != null && lc.getHiredAt().isBefore(ninetyDaysAgo)) {
                signals.add("Hired more than 90 days ago");
            }
            try {
                boolean hasFinal = internEvaluationRepository
                        .findByInternIdOrderByCreatedAtDesc(lc.getUserId()).stream()
                        .anyMatch(e -> "FINAL".equalsIgnoreCase(e.getEvaluationType())
                                && ("PUBLISHED".equalsIgnoreCase(e.getStatus())
                                    || "ACKNOWLEDGED".equalsIgnoreCase(e.getStatus())
                                    || "AMENDED".equalsIgnoreCase(e.getStatus())));
                if (hasFinal) signals.add("FINAL evaluation published");
            } catch (Exception ignored) {}
            try {
                long completed = projectAssignmentRepository
                        .findByInternIdOrderByAssignmentDateDescCreatedAtDesc(lc.getUserId())
                        .stream()
                        .filter(a -> a.getStatus() == ProjectAssignmentStatus.COMPLETED)
                        .count();
                if (completed > 0) signals.add(completed + " project(s) completed");
            } catch (Exception ignored) {}
            if (signals.isEmpty()) continue;
            User u = userRepository.findById(lc.getUserId()).orElse(null);
            out.add(new ExitDtos.PendingExitItem(
                    lc.getId(), lc.getUserId(),
                    u != null ? u.getFullName() : null,
                    u != null ? u.getEmail() : null,
                    lc.getHiredAt(),
                    signals));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<ExitDtos.ExitFeedbackResponse> getFeedbackForIntern(UUID internUserId) {
        return exitFeedbackRepository.findByInternId(internUserId).map(this::toFeedbackResponse);
    }

    @Transactional(readOnly = true)
    public Optional<ExitDtos.ExitSummaryResponse> getInternSummary(User intern) {
        Optional<ExitRecord> recordOpt = exitRecordRepository.findByInternId(intern.getId());
        if (recordOpt.isEmpty()) return Optional.empty();
        ExitRecord record = recordOpt.get();
        InternLifecycle lc = internLifecycleRepository.findById(record.getInternLifecycleId())
                .orElse(null);
        Instant hiredAt = lc != null && lc.getHiredAt() != null ? lc.getHiredAt() : record.getCreatedAt();
        Instant endedAt = lc != null && lc.getEndedAt() != null ? lc.getEndedAt() : Instant.now();
        long durationDays = Math.max(0, ChronoUnit.DAYS.between(hiredAt, endedAt));

        long projectsCompleted = 0;
        try {
            projectsCompleted = projectAssignmentRepository
                    .findByInternIdOrderByAssignmentDateDescCreatedAtDesc(intern.getId())
                    .stream()
                    .filter(a -> a.getStatus() == ProjectAssignmentStatus.COMPLETED)
                    .count();
        } catch (Exception ignored) {}

        List<InternEvaluation> evals = new ArrayList<>();
        try {
            evals = internEvaluationRepository
                    .findByInternIdOrderByCreatedAtDesc(intern.getId());
        } catch (Exception ignored) {}
        long evalsCount = evals.stream()
                .filter(e -> "PUBLISHED".equalsIgnoreCase(e.getStatus())
                        || "ACKNOWLEDGED".equalsIgnoreCase(e.getStatus())
                        || "AMENDED".equalsIgnoreCase(e.getStatus()))
                .count();
        Double avgScore = null;
        var scoredEvals = evals.stream()
                .filter(e -> e.getOverallScore() != null)
                .toList();
        if (!scoredEvals.isEmpty()) {
            avgScore = scoredEvals.stream().mapToInt(InternEvaluation::getOverallScore)
                    .average().orElse(0);
        }

        long approvedTimesheets = 0;
        BigDecimal totalApprovedHours = BigDecimal.ZERO;
        try {
            approvedTimesheets = timesheetRepository.findForIntern(intern.getId()).stream()
                    .filter(t -> t.getStatus() == TimesheetStatus.APPROVED)
                    .count();
            BigDecimal sum = timesheetRepository.sumApprovedHoursForIntern(intern.getId());
            if (sum != null) totalApprovedHours = sum;
        } catch (Exception ignored) {}

        boolean feedbackSubmitted = exitFeedbackRepository.existsByExitRecordId(record.getId());

        return Optional.of(new ExitDtos.ExitSummaryResponse(
                record.getExitType(),
                record.getExitDate(),
                durationDays,
                projectsCompleted,
                evalsCount,
                avgScore,
                approvedTimesheets,
                totalApprovedHours,
                feedbackSubmitted,
                record.getInternVisibleSummary(),
                record.getFinalEvaluationId()));
    }

    // ── Helpers used by listeners (GitHub revocation) ───────────────────────

    @Transactional
    public void recordRevocationOutcome(UUID exitRecordId, Instant attemptedAt,
                                         String summary, boolean allSucceeded) {
        ExitRecord record = exitRecordRepository.findById(exitRecordId).orElse(null);
        if (record == null) {
            log.warn("[Exit] revocation outcome for unknown record {}", exitRecordId);
            return;
        }
        record.setAccessRevocationAttemptedAt(attemptedAt);
        record.setAccessRevocationSummary(summary);
        record.setAccessRevocationDone(allSucceeded);
        if (allSucceeded) {
            record.setAccessRevocationCompletedAt(attemptedAt);
        }
        exitRecordRepository.save(record);

        // ERM Phase 7 — flip the checklist item iff revocation fully
        // succeeded. Partial failures leave the row PENDING so the ERM
        // sees the "Retry revocation" CTA on the detail page.
        if (allSucceeded) {
            flipChecklistItem(exitRecordId,
                    com.anvicorp.api.erm.exit.ExitChecklistItemRegistry.GITHUB_REVOKED,
                    com.anvicorp.api.erm.exit.ExitChecklistItemRegistry.STATUS_COMPLETE,
                    null,
                    "Auto-completed by GithubRevocationListener.");
        }
    }

    /**
     * ERM Phase 7 — UPSERT-style helper for flipping a single checklist
     * row. Used by listeners (system-actor → completedById=null) and by
     * {@code submitFeedback} below.
     */
    @Transactional
    public void flipChecklistItem(UUID exitRecordId, String itemKey,
                                   String newStatus, UUID actorId, String note) {
        try {
            var existing = exitChecklistItemRepository
                    .findByExitRecordIdAndItemKey(exitRecordId, itemKey);
            com.anvicorp.api.entity.ExitChecklistItem row = existing.orElseGet(
                    () -> com.anvicorp.api.entity.ExitChecklistItem.builder()
                            .exitRecordId(exitRecordId)
                            .itemKey(itemKey)
                            .status(com.anvicorp.api.erm.exit
                                    .ExitChecklistItemRegistry.STATUS_PENDING)
                            .build());
            // Don't downgrade a manually COMPLETE/WAIVED row back to PENDING
            // — keeps the ERM's intent sticky against scheduler / listener noise.
            if (com.anvicorp.api.erm.exit.ExitChecklistItemRegistry
                    .STATUS_PENDING.equals(newStatus)
                    && !com.anvicorp.api.erm.exit.ExitChecklistItemRegistry
                            .STATUS_PENDING.equals(row.getStatus())) {
                return;
            }
            row.setStatus(newStatus);
            if (com.anvicorp.api.erm.exit.ExitChecklistItemRegistry
                    .STATUS_COMPLETE.equals(newStatus)) {
                row.setCompletedAt(Instant.now());
                row.setCompletedById(actorId);
            }
            if (note != null && !note.isBlank()) row.setNote(note);
            exitChecklistItemRepository.save(row);
        } catch (Exception e) {
            log.warn("[Exit] flipChecklistItem failed for record {} item {}: {}",
                    exitRecordId, itemKey, e.getMessage());
        }
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────

    private ExitDtos.ExitRecordResponse toErmResponse(ExitRecord r) {
        User intern = userRepository.findById(r.getInternId()).orElse(null);
        User initiator = userRepository.findById(r.getInitiatedById()).orElse(null);
        return new ExitDtos.ExitRecordResponse(
                r.getId(),
                r.getInternLifecycleId(),
                r.getInternId(),
                intern != null ? intern.getFullName() : null,
                intern != null ? intern.getEmail() : null,
                r.getExitType(),
                r.getExitDate(),
                r.getExitReason(),
                r.getInitiatedById(),
                initiator != null ? initiator.getFullName() : null,
                r.getFinalEvaluationId(),
                r.getRehireEligible(),
                r.getAccessRevocationDone(),
                r.getAccessRevocationAttemptedAt(),
                r.getAccessRevocationSummary(),
                r.getFinalDocumentsArchived(),
                r.getInternVisibleSummary(),
                r.getInternalNotes(),
                r.getAmendedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    public ExitDtos.ExitRecordInternView toInternView(ExitRecord r) {
        return new ExitDtos.ExitRecordInternView(
                r.getId(),
                r.getExitType(),
                r.getExitDate(),
                r.getExitReason(),
                r.getFinalEvaluationId(),
                r.getRehireEligible(),
                r.getInternVisibleSummary(),
                r.getCreatedAt()
        );
    }

    private ExitDtos.ExitFeedbackResponse toFeedbackResponse(ExitFeedback f) {
        return new ExitDtos.ExitFeedbackResponse(
                f.getId(),
                f.getExitRecordId(),
                f.getOverallRating(),
                f.getLearningRating(),
                f.getMentorshipRating(),
                f.getWorkEnvironmentRating(),
                f.getWhatWentWell(),
                f.getWhatCouldImprove(),
                f.getWouldRecommend(),
                f.getAdditionalComments(),
                f.getSubmittedAt()
        );
    }

    private ExitRecord mustLoad(UUID id) {
        return exitRecordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ExitRecord not found: " + id));
    }

    private void ensureAmendmentInitiator(ExitRecord record, User actor) {
        if (actor == null || record.getInitiatedById() == null
                || !record.getInitiatedById().equals(actor.getId())) {
            throw new ForbiddenException(
                    "Only the ERM who initiated this exit can modify it");
        }
    }

    private void ensureAmendmentWindow(ExitRecord record) {
        Instant cutoff = record.getCreatedAt().plus(AMENDMENT_WINDOW_DAYS, ChronoUnit.DAYS);
        if (Instant.now().isAfter(cutoff)) {
            throw new ConflictException(
                    "Amendment window closed (records are editable for "
                            + AMENDMENT_WINDOW_DAYS + " days)");
        }
    }

    private static String normalizeType(String t) {
        if (t == null) throw new BadRequestException("exitType is required");
        String up = t.trim().toUpperCase();
        if (!EXIT_TYPES.contains(up)) {
            throw new BadRequestException("exitType must be one of " + EXIT_TYPES);
        }
        return up;
    }

    /** COMPLETED + EXTENDED → COMPLETED; RESIGNED → RESIGNED; TERMINATED → TERMINATED. */
    private static String mapToLifecycleActiveStatus(String exitType) {
        return switch (exitType) {
            case "RESIGNED" -> "RESIGNED";
            case "TERMINATED" -> "TERMINATED";
            case "COMPLETED", "EXTENDED" -> "COMPLETED";
            default -> "COMPLETED";
        };
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * ERM Phase 7 — seed the 8 standard checklist items as PENDING on
     * exit creation. Best-effort: a failure here doesn't block initiate
     * (the ERM detail page tolerates missing rows and we can backfill
     * via a follow-up if needed).
     */
    private void seedChecklistItems(UUID exitRecordId) {
        try {
            for (String key : com.anvicorp.api.erm.exit.ExitChecklistItemRegistry.ALL_ITEMS) {
                if (exitChecklistItemRepository
                        .findByExitRecordIdAndItemKey(exitRecordId, key).isPresent()) {
                    continue;
                }
                exitChecklistItemRepository.save(
                        com.anvicorp.api.entity.ExitChecklistItem.builder()
                                .exitRecordId(exitRecordId)
                                .itemKey(key)
                                .status(com.anvicorp.api.erm.exit
                                        .ExitChecklistItemRegistry.STATUS_PENDING)
                                .build());
            }
        } catch (Exception e) {
            log.warn("[Exit] checklist seed failed for record {}: {}",
                    exitRecordId, e.getMessage());
        }
    }

    private Map<String, Object> snapshotForAudit(ExitRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exitReason", r.getExitReason());
        m.put("internVisibleSummary", r.getInternVisibleSummary());
        m.put("rehireEligible", r.getRehireEligible());
        return m;
    }

    private void writeAudit(UUID actorId, UUID subjectUserId, String action,
                             String entityType, UUID entityId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog entry = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType(entityType)
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(entry);
        } catch (JsonProcessingException jpe) {
            log.warn("[Exit] audit JSON failed for {}: {}", action, jpe.getMessage());
        } catch (Exception e) {
            log.warn("[Exit] audit write failed for {}: {}", action, e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Instant cleanupCutoff(LocalDate exitDate) {
        return exitDate.plusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
