package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.report.CreateWeeklyReportRequest;
import com.anvicorp.api.dto.report.ReviewWeeklyReportRequest;
import com.anvicorp.api.dto.report.UpdateWeeklyReportRequest;
import com.anvicorp.api.dto.report.WeeklyReportResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WeeklyReportStatus;
import com.anvicorp.api.event.WeeklyReportSubmittedEvent;
import com.anvicorp.api.event.WeeklyReportVerifiedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Weekly narrative reports — second piece of the Phase-2 weekly cycle.
 *
 * <h2>Two-stage review (mirrors the timesheet flow)</h2>
 * {@code DRAFT → SUBMITTED → VERIFIED → APPROVED}, with {@code RETURNED} as
 * the send-back branch from either SUBMITTED (ERM stage) or VERIFIED
 * (Manager stage). Roles per stage:
 * <ul>
 *   <li>{@code SUBMITTED → VERIFIED}: {@code ERM} + {@code SUPER_ADMIN}.
 *       Endpoint: {@code /api/v1/erm/weekly-reports/{id}/verify}.</li>
 *   <li>{@code VERIFIED → APPROVED}: {@code MANAGER} + {@code SUPER_ADMIN}.
 *       Endpoint: {@code /api/v1/weekly-reports/{id}/approve}.</li>
 *   <li>Return-for-correction: allowed at either stage by the reviewer of
 *       that stage. Endpoint on the per-stage controller.</li>
 * </ul>
 *
 * <h2>Intern-active gate</h2>
 * A caller must have a {@link Candidate} row AND
 * {@link LifecycleAccessPolicy#ensureCanWrite} must pass — same source
 * of truth as timesheets, project submissions, and every other
 * intern-side write path.
 *
 * <h2>APPROVED-lock guard</h2>
 * Once {@code status == APPROVED} the report is frozen. PUT returns 409,
 * RETURN / APPROVE / VERIFY become idempotent no-ops.
 *
 * <h2>Reviewer scope</h2>
 * Both stages are org-wide (any ERM verifies, any Manager approves) so a
 * report never gets stuck behind one reviewer's vacation. Mirrors the
 * timesheet pattern where the ERM verify queue is shared.
 *
 * <h2>Audit actions</h2>
 * <ul>
 *   <li>REPORT_SUBMITTED — DRAFT or RETURNED → SUBMITTED (intern)</li>
 *   <li>REPORT_VERIFIED — SUBMITTED → VERIFIED (ERM)</li>
 *   <li>REPORT_RETURNED — reviewer sent back with notes</li>
 *   <li>REPORT_APPROVED — Manager approved (terminal)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyReportService {

    private final WeeklyReportRepository reportRepository;
    private final CandidateRepository candidateRepository;
    private final EngagementRepository engagementRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.anvicorp.api.notification.NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final DocumentVaultService documentVault;
    private final DocumentRepository documentRepository;
    private final LifecycleAccessPolicy lifecycleAccessPolicy;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Self-injected proxy so {@link #verifyBatch} can invoke
     * {@link #verify} VIA Spring's AOP proxy. A direct call from
     * {@code verifyBatch} bypasses the proxy (self-invocation) → the
     * inner method runs INSIDE the outer transaction, so a single
     * malformed row poisons the whole batch. Routing through the proxy
     * gives each row its own tx. Same pattern as
     * {@link com.anvicorp.api.erm.timesheet.ErmTimesheetVerifyService}.
     */
    @Autowired
    @Lazy
    private WeeklyReportService self;

    // ── Attachment constants ────────────────────────────────────────────────

    /** Max attachment size — matches {@code spring.servlet.multipart.max-file-size}. */
    private static final long ATTACHMENT_MAX_BYTES = 10L * 1024 * 1024;

    /** Document category for weekly-report attachments. */
    private static final String ATTACHMENT_CATEGORY = "WEEKLY_REPORT";

    /** Allow-list of MIME types the attachment endpoint accepts. */
    private static final Set<String> ATTACHMENT_ALLOWED_MIMES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Filename suffixes accepted when the browser sends a null / generic MIME. */
    private static final Set<String> ATTACHMENT_ALLOWED_SUFFIXES = Set.of(
            ".pdf", ".doc", ".docx");

    // ── Intern commands ─────────────────────────────────────────────────────

    @Transactional
    public WeeklyReportResponse create(CreateWeeklyReportRequest req, User actor) {
        Candidate intern = requireActiveIntern(actor);

        try {
            WeeklyReport report = WeeklyReport.builder()
                    .intern(intern)
                    .weekStart(req.getWeekStart())
                    .completedWork(req.getCompletedWork())
                    .blockers(req.getBlockers())
                    .learningOutcomes(req.getLearningOutcomes())
                    .nextPlan(req.getNextPlan())
                    .status(WeeklyReportStatus.DRAFT)
                    .build();
            report = reportRepository.save(report);
            WeeklyReport saved = reportRepository.findByIdWithGraph(report.getId())
                    .orElse(report);
            return toResponse(saved);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException(
                    "A weekly report already exists for that week. Open the existing draft to edit.");
        }
    }

    @Transactional
    public WeeklyReportResponse update(UUID reportId,
                                       UpdateWeeklyReportRequest req,
                                       User actor) {
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));
        ensureInternOwner(report, actor);

        if (report.getStatus() == WeeklyReportStatus.APPROVED) {
            throw new ConflictException(
                    "This report has been approved and is locked. Start a new week's report.");
        }

        if (req.getWeekStart() != null) report.setWeekStart(req.getWeekStart());
        if (req.getCompletedWork() != null) report.setCompletedWork(req.getCompletedWork());
        if (req.getBlockers() != null) report.setBlockers(req.getBlockers());
        if (req.getLearningOutcomes() != null) report.setLearningOutcomes(req.getLearningOutcomes());
        if (req.getNextPlan() != null) report.setNextPlan(req.getNextPlan());

        boolean submitting = Boolean.TRUE.equals(req.getSubmit())
                && (report.getStatus() == WeeklyReportStatus.DRAFT
                    || report.getStatus() == WeeklyReportStatus.RETURNED);

        if (submitting) {
            report.setStatus(WeeklyReportStatus.SUBMITTED);
            report.setSubmittedAt(Instant.now());
            // Clear stale reviewer stamps on resubmit — the report goes
            // back to the ERM queue as if fresh. verified_at is cleared so
            // an evaluator can't accidentally see a stale VERIFIED badge on
            // a row that's been sent back and re-submitted.
            report.setVerifiedBy(null);
            report.setVerifiedAt(null);
            report.setErmNotes(null);
        }

        WeeklyReport saved;
        try {
            saved = reportRepository.save(report);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException(
                    "Another report already exists for that week.");
        }

        if (submitting) {
            writeAudit(saved.getId(), "REPORT_SUBMITTED", actor.getId(),
                    Map.of("weekStart", saved.getWeekStart().toString(),
                           "internCandidateId", saved.getIntern().getId()));
            publishChainEvent(new WeeklyReportSubmittedEvent(
                    saved.getId(),
                    saved.getIntern() != null && saved.getIntern().getUser() != null
                            ? saved.getIntern().getUser().getId() : null,
                    actor.getId()));
        }

        WeeklyReport refreshed = reportRepository.findByIdWithGraph(saved.getId())
                .orElse(saved);
        return toResponse(refreshed);
    }

    @Transactional(readOnly = true)
    public List<WeeklyReportResponse> listForMe(User actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Weekly reports are available to interns only."));
        return reportRepository.findByInternIdWithGraph(candidate.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Reviewer commands (shared) ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WeeklyReportResponse> listForCandidate(UUID candidateId, User actor) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));
        ensureReviewerCanRead(candidate, actor);
        return reportRepository.findByInternIdWithGraph(candidate.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Stage 1: ERM verify ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WeeklyReportResponse> listSubmittedForErm(User actor) {
        ensureErmOrSuperAdmin(actor, "view the ERM weekly-report queue");
        return reportRepository.findAllByStatusWithGraph(WeeklyReportStatus.SUBMITTED)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * ERM verifies a SUBMITTED report. Transitions SUBMITTED → VERIFIED and
     * stamps {@code verified_by} / {@code verified_at}. Idempotent on
     * VERIFIED / APPROVED. Rejects DRAFT / RETURNED with 400.
     */
    @Transactional
    public WeeklyReportResponse verify(UUID reportId,
                                       ReviewWeeklyReportRequest req,
                                       User actor) {
        ensureErmOrSuperAdmin(actor, "verify a weekly report");
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));

        if (report.getStatus() == WeeklyReportStatus.APPROVED
                || report.getStatus() == WeeklyReportStatus.VERIFIED) {
            // Idempotent: already past this stage.
            return toResponse(report);
        }
        if (report.getStatus() != WeeklyReportStatus.SUBMITTED) {
            throw new BadRequestException(
                    "Only SUBMITTED weekly reports can be verified (current: "
                            + report.getStatus() + ")");
        }

        report.setStatus(WeeklyReportStatus.VERIFIED);
        report.setVerifiedBy(actor);
        report.setVerifiedAt(Instant.now());
        if (req != null && req.getErmNotes() != null && !req.getErmNotes().isBlank()) {
            report.setErmNotes(req.getErmNotes().trim());
        }
        WeeklyReport saved = reportRepository.save(report);

        writeAudit(saved.getId(), "REPORT_VERIFIED", actor.getId(),
                Map.of("weekStart", saved.getWeekStart().toString(),
                       "internCandidateId", saved.getIntern().getId()));
        publishChainEvent(new WeeklyReportVerifiedEvent(
                saved.getId(),
                saved.getIntern() != null && saved.getIntern().getUser() != null
                        ? saved.getIntern().getUser().getId() : null,
                actor.getId()));

        WeeklyReport refreshed = reportRepository.findByIdWithGraph(saved.getId())
                .orElse(saved);
        return toResponse(refreshed);
    }

    /**
     * Batch-verify a set of ids. Iterates via the self-injected proxy so
     * each row runs in its OWN transaction — a per-row failure (wrong
     * state, forbidden, DB check) rolls back only that row and is
     * reflected in the per-id outcome map instead of poisoning the
     * commit. Mirrors {@code ErmTimesheetVerifyService.verifyBatch}.
     *
     * <p>Deliberately NOT {@code @Transactional} — self-invocation via
     * the proxy is the whole point.</p>
     */
    public Map<UUID, String> verifyBatch(List<UUID> ids, User actor) {
        // Role gate once up-front so a wholly-forbidden caller doesn't
        // silently 200 with every id marked FORBIDDEN.
        ensureErmOrSuperAdmin(actor, "verify weekly reports");
        Map<UUID, String> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (UUID id : ids) {
            try {
                self.verify(id, null, actor);
                out.put(id, "VERIFIED");
            } catch (ForbiddenException fe) {
                out.put(id, "FORBIDDEN");
            } catch (BadRequestException br) {
                // Wrong-state rows land here — surface the state message
                // so the UI can show which weeks were skipped and why.
                String m = br.getMessage() == null ? "BAD_REQUEST" : br.getMessage();
                out.put(id, m.length() > 200 ? m.substring(0, 200) : m);
            } catch (Exception e) {
                String m = e.getMessage() == null ? "FAILED" : e.getMessage();
                out.put(id, m.length() > 200 ? m.substring(0, 200) : m);
            }
        }
        return out;
    }

    /**
     * ERM returns a SUBMITTED report for correction. Distinct entry point
     * from the Manager's return (which acts on VERIFIED) — same target
     * status ({@code RETURNED}) but with a stage-specific state check so a
     * misconfigured button can't skip a stage.
     */
    @Transactional
    public WeeklyReportResponse returnFromErm(UUID reportId,
                                              ReviewWeeklyReportRequest req,
                                              User actor) {
        ensureErmOrSuperAdmin(actor, "return a weekly report");
        return returnCommon(reportId, req, actor, WeeklyReportStatus.SUBMITTED, "ERM");
    }

    // ── Stage 2: Manager approve ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WeeklyReportResponse> listVerifiedForManager(User actor) {
        ensureManagerOrSuperAdmin(actor, "view the Manager weekly-report queue");
        return reportRepository.findAllByStatusWithGraph(WeeklyReportStatus.VERIFIED)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Manager approves a VERIFIED report. Requires the ERM verify stage
     * to have happened — a still-SUBMITTED row returns 400 with a clear
     * message. Mirrors the timesheet flow's {@code TimesheetService.approve}
     * VERIFIED-required guard.
     */
    @Transactional
    public WeeklyReportResponse approve(UUID reportId,
                                        ReviewWeeklyReportRequest req,
                                        User actor) {
        ensureManagerOrSuperAdmin(actor, "approve a weekly report");
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));

        if (report.getStatus() == WeeklyReportStatus.APPROVED) {
            return toResponse(report);
        }
        if (report.getStatus() == WeeklyReportStatus.DRAFT) {
            throw new BadRequestException(
                    "Can't approve a DRAFT report — it hasn't been submitted yet.");
        }
        if (report.getStatus() != WeeklyReportStatus.VERIFIED) {
            throw new BadRequestException(
                    "Only VERIFIED reports can be approved (current: "
                            + report.getStatus()
                            + "). The ERM must verify the report first.");
        }

        report.setStatus(WeeklyReportStatus.APPROVED);
        report.setReviewedBy(actor);
        if (req != null && req.getReviewNotes() != null && !req.getReviewNotes().isBlank()) {
            report.setReviewNotes(req.getReviewNotes().trim());
        }
        report.setReviewedAt(Instant.now());
        WeeklyReport saved = reportRepository.save(report);

        writeAudit(saved.getId(), "REPORT_APPROVED", actor.getId(),
                Map.of("weekStart", saved.getWeekStart().toString(),
                       "internCandidateId", saved.getIntern().getId()));

        WeeklyReport refreshed = reportRepository.findByIdWithGraph(saved.getId())
                .orElse(saved);
        try {
            notificationService.sendWeeklyReportApproved(refreshed);
        } catch (Exception e) {
            log.warn("WEEKLY_REPORT_APPROVED notify failed (non-fatal) for {}: {}",
                    refreshed.getId(), e.getMessage());
        }
        return toResponse(refreshed);
    }

    /**
     * Manager returns a VERIFIED report for correction. Same target
     * status ({@code RETURNED}) as the ERM return, but keyed off the
     * VERIFIED source state so a mis-routed button can't skip stages.
     */
    @Transactional
    public WeeklyReportResponse returnFromManager(UUID reportId,
                                                  ReviewWeeklyReportRequest req,
                                                  User actor) {
        ensureManagerOrSuperAdmin(actor, "return a weekly report");
        return returnCommon(reportId, req, actor, WeeklyReportStatus.VERIFIED, "Manager");
    }

    // ── Return-for-correction (shared body) ─────────────────────────────────

    private WeeklyReportResponse returnCommon(UUID reportId,
                                              ReviewWeeklyReportRequest req,
                                              User actor,
                                              WeeklyReportStatus fromState,
                                              String stageWord) {
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));

        if (report.getStatus() == WeeklyReportStatus.APPROVED) {
            // Terminal — idempotent no-op.
            return toResponse(report);
        }
        if (report.getStatus() == WeeklyReportStatus.DRAFT) {
            throw new BadRequestException(
                    "Can't return a DRAFT report — it hasn't been submitted yet.");
        }
        if (report.getStatus() != fromState) {
            throw new BadRequestException(
                    "This report is currently " + report.getStatus()
                            + ". The " + stageWord + " can only return reports in state "
                            + fromState + ".");
        }
        if (req == null || req.getReviewNotes() == null || req.getReviewNotes().isBlank()) {
            throw new BadRequestException(
                    "Review notes are required when returning a report for correction.");
        }

        report.setStatus(WeeklyReportStatus.RETURNED);
        report.setReviewedBy(actor);
        report.setReviewNotes(req.getReviewNotes().trim());
        report.setReviewedAt(Instant.now());
        // Clear the verify stamp when an evaluator sends a VERIFIED row
        // back — the row must re-run the ERM stage after intern re-submits,
        // so the verify attribution should not leak forward.
        if (fromState == WeeklyReportStatus.VERIFIED) {
            report.setVerifiedBy(null);
            report.setVerifiedAt(null);
            report.setErmNotes(null);
        }
        WeeklyReport saved = reportRepository.save(report);

        writeAudit(saved.getId(), "REPORT_RETURNED", actor.getId(),
                Map.of("weekStart", saved.getWeekStart().toString(),
                       "internCandidateId", saved.getIntern().getId(),
                       "fromStage", stageWord));

        WeeklyReport refreshed = reportRepository.findByIdWithGraph(saved.getId())
                .orElse(saved);
        try {
            notificationService.sendWeeklyReportReturned(refreshed);
        } catch (Exception e) {
            log.warn("WEEKLY_REPORT_RETURNED notify failed (non-fatal) for {}: {}",
                    refreshed.getId(), e.getMessage());
        }
        return toResponse(refreshed);
    }

    // ── Gate helpers ────────────────────────────────────────────────────────

    private Candidate requireActiveIntern(User candidateUser) {
        Candidate candidate = candidateRepository.findByUserId(candidateUser.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Weekly reports are available to active interns only."));
        lifecycleAccessPolicy.ensureCanWrite(
                candidateUser,
                candidateUser.getId(),
                LifecycleAccessPolicy.WriteIntent.CREATE_NEW);
        return candidate;
    }

    /**
     * Intern-owner gate: the report belongs to the caller's Candidate row.
     * No SUPER_ADMIN bypass here — admins don't author reports on someone
     * else's behalf; they use the reviewer surfaces instead.
     */
    private void ensureInternOwner(WeeklyReport report, User actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Weekly reports are available to interns only."));
        if (report.getIntern() == null
                || !report.getIntern().getId().equals(candidate.getId())) {
            throw new ResourceNotFoundException(
                    "Weekly report not found: " + report.getId());
        }
    }

    /**
     * Read-side gate for a specific candidate's reports. Any ERM /
     * MANAGER / EVALUATOR / SUPER_ADMIN sees any intern's report list.
     * Legacy supervisor (engagement.supervisor.id == actor.id, typically
     * the TRAINER) is also allowed so trainers can still see reports for
     * their own intern roster — they just can't act on them any more
     * (approve / return live on the ERM + Manager surfaces). EVALUATOR
     * keeps read access so evaluators still see report history when
     * preparing monthly / final evaluations.
     */
    private void ensureReviewerCanRead(Candidate candidate, User actor) {
        if (actor == null) {
            throw new ForbiddenException("Authentication required.");
        }
        Set<UserRole> roles = actor.getRoles();
        if (roles != null && (roles.contains(UserRole.SUPER_ADMIN)
                || roles.contains(UserRole.ERM)
                || roles.contains(UserRole.MANAGER)
                || roles.contains(UserRole.EVALUATOR))) {
            return;
        }
        List<Engagement> engagements = engagementRepository.findByCandidateId(candidate.getId());
        boolean owns = engagements.stream()
                .anyMatch(e -> e.getSupervisor() != null
                        && e.getSupervisor().getId().equals(actor.getId()));
        if (!owns) {
            throw new ForbiddenException(
                    "Only this intern's ERM, Manager, Evaluator, supervisor, "
                            + "or SUPER_ADMIN may read their reports.");
        }
    }

    private static void ensureErmOrSuperAdmin(User actor, String action) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        Set<UserRole> roles = actor.getRoles();
        if (roles == null) throw new ForbiddenException("ERM role required to " + action);
        if (roles.contains(UserRole.SUPER_ADMIN) || roles.contains(UserRole.ERM)) return;
        throw new ForbiddenException("ERM role required to " + action);
    }

    private static void ensureManagerOrSuperAdmin(User actor, String action) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        Set<UserRole> roles = actor.getRoles();
        if (roles == null) throw new ForbiddenException("MANAGER role required to " + action);
        if (roles.contains(UserRole.SUPER_ADMIN) || roles.contains(UserRole.MANAGER)) return;
        throw new ForbiddenException("MANAGER role required to " + action);
    }

    private void publishChainEvent(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception ignored) {
            // Listeners are best-effort; the publisher shouldn't crash a
            // state transition.
        }
    }

    // ── Attachment (optional supporting file) ───────────────────────────────

    @Transactional
    public WeeklyReportResponse uploadAttachment(UUID reportId,
                                                 MultipartFile file,
                                                 User actor) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > ATTACHMENT_MAX_BYTES) {
            throw new BadRequestException(
                    "Attachment exceeds " + (ATTACHMENT_MAX_BYTES / (1024 * 1024))
                            + " MB limit.");
        }
        String mime = file.getContentType();
        String filename = file.getOriginalFilename();
        boolean mimeOk = mime != null && ATTACHMENT_ALLOWED_MIMES.contains(mime.toLowerCase());
        boolean suffixOk = filename != null
                && ATTACHMENT_ALLOWED_SUFFIXES.stream()
                    .anyMatch(s -> filename.toLowerCase().endsWith(s));
        if (!mimeOk && !(mime == null && suffixOk)) {
            throw new BadRequestException(
                    "Only PDF / DOC / DOCX files are accepted for weekly-report attachments.");
        }

        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));
        ensureInternOwner(report, actor);

        if (report.getStatus() == WeeklyReportStatus.APPROVED) {
            throw new ConflictException(
                    "This report has been approved and is locked. Start a new week's report.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new BadRequestException("Could not read uploaded bytes: " + e.getMessage());
        }

        Document saved = documentVault.saveDocument(
                actor.getId(),
                filename != null ? filename : "weekly-report.pdf",
                mime != null ? mime : "application/pdf",
                bytes,
                ATTACHMENT_CATEGORY,
                "NORMAL",
                actor.getId());

        UUID previousDocId = report.getAttachmentDocumentId();
        report.setAttachmentDocumentId(saved.getId());
        WeeklyReport savedReport = reportRepository.save(report);

        if (previousDocId != null && !previousDocId.equals(saved.getId())) {
            try {
                documentRepository.findById(previousDocId).ifPresent(prior -> {
                    if (prior.getDeletedAt() == null) {
                        prior.setDeletedAt(Instant.now());
                        documentRepository.save(prior);
                    }
                });
            } catch (Exception e) {
                log.warn("[WeeklyReport] prior-attachment soft-delete failed (non-fatal) "
                        + "for report {} prev doc {}: {}",
                        savedReport.getId(), previousDocId, e.getMessage());
            }
        }

        WeeklyReport refreshed = reportRepository.findByIdWithGraph(savedReport.getId())
                .orElse(savedReport);
        return toResponse(refreshed);
    }

    /**
     * Load the attachment bytes for streaming. Intern-owner OR
     * ERM / MANAGER / EVALUATOR / SUPER_ADMIN OR the assigned Trainer
     * supervisor.
     */
    @Transactional
    public AttachmentPayload readAttachment(UUID reportId, User actor) {
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));
        UUID docId = report.getAttachmentDocumentId();
        if (docId == null) {
            throw new ResourceNotFoundException(
                    "This weekly report has no attachment.");
        }
        ensureCanReadAttachment(report, actor);

        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attachment document not found: " + docId));
        if (doc.getDeletedAt() != null) {
            throw new ResourceNotFoundException(
                    "Attachment document not found: " + docId);
        }
        byte[] bytes = documentVault.readDocumentBytesNoAuth(docId);
        return new AttachmentPayload(doc, bytes);
    }

    @Transactional
    public WeeklyReportResponse deleteAttachment(UUID reportId, User actor) {
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));
        ensureInternOwner(report, actor);
        if (report.getStatus() == WeeklyReportStatus.APPROVED) {
            throw new ConflictException(
                    "This report has been approved and is locked.");
        }

        UUID previousDocId = report.getAttachmentDocumentId();
        if (previousDocId == null) {
            return toResponse(report);
        }
        report.setAttachmentDocumentId(null);
        WeeklyReport savedReport = reportRepository.save(report);
        try {
            documentRepository.findById(previousDocId).ifPresent(prior -> {
                if (prior.getDeletedAt() == null) {
                    prior.setDeletedAt(Instant.now());
                    documentRepository.save(prior);
                }
            });
        } catch (Exception e) {
            log.warn("[WeeklyReport] attachment soft-delete failed (non-fatal) "
                    + "for report {} doc {}: {}",
                    savedReport.getId(), previousDocId, e.getMessage());
        }
        WeeklyReport refreshed = reportRepository.findByIdWithGraph(savedReport.getId())
                .orElse(savedReport);
        return toResponse(refreshed);
    }

    /**
     * Attachment-read RBAC: intern-owner OR SUPER_ADMIN OR any ERM /
     * MANAGER / EVALUATOR (all now have queue or history surfaces that
     * need to preview the file) OR the assigned Trainer supervisor.
     * Mirrors {@link #ensureReviewerCanRead} but also allows the
     * intern-owner.
     */
    private void ensureCanReadAttachment(WeeklyReport report, User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        Set<UserRole> roles = actor.getRoles();
        if (roles != null && (roles.contains(UserRole.SUPER_ADMIN)
                || roles.contains(UserRole.ERM)
                || roles.contains(UserRole.MANAGER)
                || roles.contains(UserRole.EVALUATOR))) {
            return;
        }
        Candidate ownerIntern = report.getIntern();
        if (ownerIntern != null) {
            Candidate mine = candidateRepository.findByUserId(actor.getId()).orElse(null);
            if (mine != null && ownerIntern.getId().equals(mine.getId())) {
                return;
            }
            List<Engagement> engagements = engagementRepository
                    .findByCandidateId(ownerIntern.getId());
            boolean owns = engagements.stream()
                    .anyMatch(e -> e.getSupervisor() != null
                            && e.getSupervisor().getId().equals(actor.getId()));
            if (owns) return;
        }
        throw new ForbiddenException(
                "Only this intern, an ERM/Manager/Evaluator/supervisor, "
                        + "or SUPER_ADMIN may read this attachment.");
    }

    /** Bytes + metadata for streaming the attachment out through the controller. */
    public record AttachmentPayload(Document document, byte[] bytes) {}

    // ── Mapping ─────────────────────────────────────────────────────────────

    private WeeklyReportResponse toResponse(WeeklyReport r) {
        Candidate intern = r.getIntern();
        User internUser = intern != null ? intern.getUser() : null;
        User reviewer = r.getReviewedBy();
        User verifier = r.getVerifiedBy();
        WeeklyReportResponse.WeeklyReportResponseBuilder b = WeeklyReportResponse.builder()
                .id(r.getId())
                .internCandidateId(intern != null ? intern.getId() : null)
                .internName(internUser != null ? internUser.getFullName() : null)
                .weekStart(r.getWeekStart())
                .completedWork(r.getCompletedWork())
                .blockers(r.getBlockers())
                .learningOutcomes(r.getLearningOutcomes())
                .nextPlan(r.getNextPlan())
                .status(r.getStatus())
                .submittedAt(r.getSubmittedAt())
                .reviewedById(reviewer != null ? reviewer.getId() : null)
                .reviewedByName(reviewer != null ? reviewer.getFullName() : null)
                .reviewNotes(r.getReviewNotes())
                .reviewedAt(r.getReviewedAt())
                .verifiedById(verifier != null ? verifier.getId() : null)
                .verifiedByName(verifier != null ? verifier.getFullName() : null)
                .verifiedAt(r.getVerifiedAt())
                .ermNotes(r.getErmNotes())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt());
        if (r.getAttachmentDocumentId() != null) {
            b.attachmentDocumentId(r.getAttachmentDocumentId())
                    .attachmentDownloadUrl(
                            "/api/v1/weekly-reports/" + r.getId() + "/attachment");
            documentRepository.findById(r.getAttachmentDocumentId()).ifPresent(doc -> {
                if (doc.getDeletedAt() != null) return;
                b.attachmentFileName(doc.getFileName())
                        .attachmentFileSize(doc.getFileSize())
                        .attachmentMimeType(doc.getMimeType());
            });
        }
        return b.build();
    }

    // ── Audit ───────────────────────────────────────────────────────────────

    private void writeAudit(UUID reportId, String action, UUID userId,
                            Map<String, Object> snapshot) {
        Map<String, Object> after = snapshot != null
                ? new LinkedHashMap<>(snapshot) : new LinkedHashMap<>();
        AuditLog entry = AuditLog.builder()
                .entityType("WeeklyReport")
                .entityId(reportId)
                .action(action)
                .userId(userId)
                .afterJson(serializeJson(after))
                .build();
        auditLogRepository.save(entry);
    }

    private String serializeJson(Map<String, Object> snapshot) {
        if (snapshot == null) return null;
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize report audit snapshot: {}", e.getMessage());
            return new HashMap<>(snapshot).toString();
        }
    }
}
