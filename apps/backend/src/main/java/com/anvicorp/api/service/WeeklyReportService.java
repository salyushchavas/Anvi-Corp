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
import com.anvicorp.api.entity.Engagement; // still used by supervisor / attachment guards below
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WeeklyReportStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Weekly narrative reports — second piece of the Phase-2 weekly cycle.
 *
 * <h2>Intern-active gate</h2>
 * A caller must have a {@link Candidate} row AND
 * {@link LifecycleAccessPolicy#ensureCanWrite} must pass — same source
 * of truth as timesheets, project submissions, and every other
 * intern-side write path. Pre-hire applicants (no candidate row) and
 * post-exit interns (past the cleanup window) are refused.
 *
 * <h2>APPROVED-lock guard</h2>
 * Once {@code status == APPROVED} the report is frozen. PUT returns 409,
 * RETURN / APPROVE are silent idempotent no-ops (so a supervisor
 * double-click on Approve doesn't 400).
 *
 * <h2>Supervisor ownership</h2>
 * The TECHNICAL_EVALUATOR can only review reports for interns whose active
 * engagement they own ({@code engagement.supervisor.id == actor.id}).
 * SUPER_ADMIN bypasses the ownership check.
 *
 * <h2>Audit actions</h2>
 * <ul>
 *   <li>REPORT_SUBMITTED — DRAFT or RETURNED → SUBMITTED (intern)</li>
 *   <li>REPORT_RETURNED — supervisor sent back with notes</li>
 *   <li>REPORT_APPROVED — supervisor approved (terminal)</li>
 * </ul>
 * No CREATE / UPDATE audit — only lifecycle transitions are recorded.
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
            // Re-fetch with graph so the response carries intern.user / reviewer
            // without lazy-loading after the transaction closes.
            WeeklyReport saved = reportRepository.findByIdWithGraph(report.getId())
                    .orElse(report);
            return toResponse(saved);
        } catch (DataIntegrityViolationException dup) {
            // The unique constraint on (intern_id, week_start) tripped — the
            // intern already has a row for this week. Surface as 409 with a
            // helpful pointer to PUT the existing row.
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
            // Hard lock — supervisor's signed off, intern can't edit any more.
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
        }

        WeeklyReport saved;
        try {
            saved = reportRepository.save(report);
        } catch (DataIntegrityViolationException dup) {
            // Edge case: intern changed weekStart to one that collides with
            // another of their own existing rows. Treat as user error.
            throw new ConflictException(
                    "Another report already exists for that week.");
        }

        if (submitting) {
            writeAudit(saved.getId(), "REPORT_SUBMITTED", actor.getId(),
                    Map.of("weekStart", saved.getWeekStart().toString(),
                           "internCandidateId", saved.getIntern().getId()));
        }

        WeeklyReport refreshed = reportRepository.findByIdWithGraph(saved.getId())
                .orElse(saved);
        return toResponse(refreshed);
    }

    @Transactional(readOnly = true)
    public List<WeeklyReportResponse> listForMe(User actor) {
        // The intern self-view doesn't require an ACTIVE engagement — once
        // they've ever filed reports, they should still see the history (e.g.
        // engagement COMPLETED). We resolve the candidate by user id and
        // return whatever rows exist.
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Weekly reports are available to interns only."));
        return reportRepository.findByInternIdWithGraph(candidate.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Supervisor commands ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WeeklyReportResponse> listForCandidate(UUID candidateId, User actor) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));
        ensureSupervisorCanReview(candidate, actor);
        return reportRepository.findByInternIdWithGraph(candidate.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public WeeklyReportResponse returnForCorrection(UUID reportId,
                                                    ReviewWeeklyReportRequest req,
                                                    User actor) {
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));
        ensureSupervisorCanReview(report.getIntern(), actor);

        if (report.getStatus() == WeeklyReportStatus.APPROVED) {
            // Already locked — idempotent no-op so a stale supervisor click
            // doesn't 400.
            return toResponse(report);
        }
        if (report.getStatus() == WeeklyReportStatus.DRAFT) {
            throw new BadRequestException(
                    "Can't return a DRAFT report — it hasn't been submitted yet.");
        }
        if (req == null || req.getReviewNotes() == null || req.getReviewNotes().isBlank()) {
            throw new BadRequestException(
                    "Review notes are required when returning a report for correction.");
        }

        report.setStatus(WeeklyReportStatus.RETURNED);
        report.setReviewedBy(actor);
        report.setReviewNotes(req.getReviewNotes().trim());
        report.setReviewedAt(Instant.now());
        WeeklyReport saved = reportRepository.save(report);

        writeAudit(saved.getId(), "REPORT_RETURNED", actor.getId(),
                Map.of("weekStart", saved.getWeekStart().toString(),
                       "internCandidateId", saved.getIntern().getId()));

        WeeklyReport refreshed = reportRepository.findByIdWithGraph(saved.getId())
                .orElse(saved);
        // Batch-3 — intern gets a "your report needs changes" email with
        // the supervisor's review notes. Best-effort.
        try {
            notificationService.sendWeeklyReportReturned(refreshed);
        } catch (Exception e) {
            log.warn("WEEKLY_REPORT_RETURNED notify failed (non-fatal) for {}: {}",
                    refreshed.getId(), e.getMessage());
        }
        return toResponse(refreshed);
    }

    @Transactional
    public WeeklyReportResponse approve(UUID reportId,
                                        ReviewWeeklyReportRequest req,
                                        User actor) {
        WeeklyReport report = reportRepository.findByIdWithGraph(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Weekly report not found: " + reportId));
        ensureSupervisorCanReview(report.getIntern(), actor);

        if (report.getStatus() == WeeklyReportStatus.APPROVED) {
            // Already locked — idempotent no-op.
            return toResponse(report);
        }
        if (report.getStatus() == WeeklyReportStatus.DRAFT) {
            throw new BadRequestException(
                    "Can't approve a DRAFT report — it hasn't been submitted yet.");
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
        // Batch-3 — intern gets a "nice work, approved" email. Best-effort.
        try {
            notificationService.sendWeeklyReportApproved(refreshed);
        } catch (Exception e) {
            log.warn("WEEKLY_REPORT_APPROVED notify failed (non-fatal) for {}: {}",
                    refreshed.getId(), e.getMessage());
        }
        return toResponse(refreshed);
    }

    // ── Gate helpers ────────────────────────────────────────────────────────

    /**
     * Intern-active gate. Delegates to the same
     * {@link LifecycleAccessPolicy#ensureCanWrite} check that timesheets,
     * project submissions, and every other intern-side write path
     * already use — a single source of truth for "can this intern do
     * work-side actions right now?"
     *
     * <p>Rationale for dropping the previous engagement-status filter:
     * an intern can have {@code User.lifecycleStatus = ACTIVE_INTERN}
     * (with projects assigned and submissions accepted) while their
     * {@code Engagement.status} sits in an off-track value like
     * {@code READY_TO_START} or even null on legacy rows. Gating
     * weekly reports on Engagement.status alone refused people the
     * rest of the platform treats as active. Now we only check:</p>
     * <ol>
     *   <li>The actor has a {@link Candidate} row (else 403 — APPLICANTs
     *       never file reports).</li>
     *   <li>{@code LifecycleAccessPolicy.ensureCanWrite} passes — same
     *       policy timesheets use. Throws {@code LifecycleClosedException}
     *       if the intern has exited past the cleanup window.</li>
     * </ol>
     */
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
     * else's behalf; they use the supervisor review surface instead.
     */
    private void ensureInternOwner(WeeklyReport report, User actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Weekly reports are available to interns only."));
        if (report.getIntern() == null
                || !report.getIntern().getId().equals(candidate.getId())) {
            // Don't leak existence — use the same 404-shape as if the row
            // didn't exist.
            throw new ResourceNotFoundException(
                    "Weekly report not found: " + report.getId());
        }
    }

    /**
     * Supervisor review gate: SUPER_ADMIN bypasses; otherwise the actor must
     * be the supervisor on this candidate's most-recent in-funnel engagement.
     */
    private void ensureSupervisorCanReview(Candidate candidate, User actor) {
        if (actor == null) {
            throw new ForbiddenException("Authentication required.");
        }
        if (actor.getRoles() != null && actor.getRoles().contains(UserRole.SUPER_ADMIN)) {
            return;
        }
        // Look at the candidate's engagements — any ACTIVE one whose
        // supervisor matches the actor counts. We accept any-status
        // engagement here so a supervisor can review reports that were
        // filed during ACTIVE even if the engagement has since COMPLETED.
        List<Engagement> engagements = engagementRepository.findByCandidateId(candidate.getId());
        boolean owns = engagements.stream()
                .anyMatch(e -> e.getSupervisor() != null
                        && e.getSupervisor().getId().equals(actor.getId()));
        if (!owns) {
            throw new ForbiddenException(
                    "Only this intern's supervisor (or SUPER_ADMIN) may review their reports.");
        }
    }

    // ── Attachment (optional supporting file) ───────────────────────────────

    /**
     * Intern uploads (or replaces) the attachment on their own report.
     * Reuses the shared {@link DocumentVaultService} multipart-through-backend
     * path — same 10 MB cap and same {@code Document} row shape as the
     * document-packet uploads. Replace semantics: any prior attachment is
     * soft-deleted at the {@code Document} row level so the vault list
     * shows only the latest one.
     *
     * <p>APPROVED reports are locked (same rule as {@link #update} — once
     * the supervisor's signed off, no more edits). DRAFT / SUBMITTED /
     * RETURNED are all fine.</p>
     */
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

        // Route through the shared vault so the attachment lives in the
        // same documents/{ownerUserId}/{uuid}.bin location as everything
        // else — no per-feature storage sprawl.
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

        // Soft-delete the previous attachment row so the vault gallery
        // doesn't accumulate abandoned uploads on replace. Best-effort —
        // failure here doesn't roll back the pointer swap (the new file
        // is what the reviewer needs to see).
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
     * TRAINER (assigned supervisor) OR SUPER_ADMIN. Returns bytes +
     * the {@link Document} metadata so the controller can set
     * Content-Type / Content-Disposition on the response.
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
        // Bypass DocumentVaultService's owner/staff RBAC — we already
        // enforced the weekly-report-scoped access rule (intern-owner OR
        // assigned supervisor OR SUPER_ADMIN) above.
        byte[] bytes = documentVault.readDocumentBytesNoAuth(docId);
        return new AttachmentPayload(doc, bytes);
    }

    /**
     * Intern clears the attachment on their own report. Same
     * APPROVED-lock as {@link #update}. Idempotent — no-op when the
     * report already has no attachment.
     */
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
     * Attachment-read RBAC: intern-owner OR SUPER_ADMIN OR the assigned
     * supervisor (TRAINER) on any of this candidate's engagements. Mirrors
     * {@link #ensureSupervisorCanReview} but also allows the intern-owner
     * (who obviously can see their own attachment).
     */
    private void ensureCanReadAttachment(WeeklyReport report, User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        // SUPER_ADMIN — full bypass.
        if (actor.getRoles() != null && actor.getRoles().contains(UserRole.SUPER_ADMIN)) {
            return;
        }
        // Intern-owner path.
        Candidate ownerIntern = report.getIntern();
        if (ownerIntern != null) {
            Candidate mine = candidateRepository.findByUserId(actor.getId()).orElse(null);
            if (mine != null && ownerIntern.getId().equals(mine.getId())) {
                return;
            }
        }
        // Supervisor path — any engagement whose supervisor is the actor.
        if (ownerIntern != null) {
            List<Engagement> engagements = engagementRepository
                    .findByCandidateId(ownerIntern.getId());
            boolean owns = engagements.stream()
                    .anyMatch(e -> e.getSupervisor() != null
                            && e.getSupervisor().getId().equals(actor.getId()));
            if (owns) return;
        }
        throw new ForbiddenException(
                "Only this intern, their supervisor, or SUPER_ADMIN may read this attachment.");
    }

    /** Bytes + metadata for streaming the attachment out through the controller. */
    public record AttachmentPayload(Document document, byte[] bytes) {}

    // ── Mapping ─────────────────────────────────────────────────────────────

    private WeeklyReportResponse toResponse(WeeklyReport r) {
        Candidate intern = r.getIntern();
        User internUser = intern != null ? intern.getUser() : null;
        User reviewer = r.getReviewedBy();
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
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt());
        if (r.getAttachmentDocumentId() != null) {
            b.attachmentDocumentId(r.getAttachmentDocumentId())
                    .attachmentDownloadUrl(
                            "/api/v1/weekly-reports/" + r.getId() + "/attachment");
            // Fetch the Document metadata for filename / size / mime.
            // findById is cheap (single row) and the response set is small
            // (one report at a time, or a candidate's history — bounded).
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
