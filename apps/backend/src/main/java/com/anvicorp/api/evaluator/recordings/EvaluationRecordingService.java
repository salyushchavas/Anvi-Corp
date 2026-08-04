package com.anvicorp.api.evaluator.recordings;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.ProjectAssignment;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluator session-recording flow — mirrors the ERM interview-recording
 * pattern ({@code ErmInterviewRecordingService}) verbatim. Videos are
 * too large for the multipart cap; browser PUTs bytes directly to S3
 * against a presigned URL minted here, then re-submits the returned
 * {@code documentId} to persist the reference on the evaluation.
 *
 * <p>Playback endpoint mints a presigned GET URL; access is gated on
 * (a) the evaluator who owns the evaluation, (b) ERM, (c) MANAGER, or
 * (d) SUPER_ADMIN. The gallery endpoint returns evaluation rows that
 * carry a {@code recordingDocumentId}, joined against Document + User
 * + Project so the frontend can group by month → intern → project
 * without extra fetches.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationRecordingService {

    public static final String CATEGORY = "EVALUATION_RECORDING";

    // Manager approval gate values — mirrored on the DB CHECK rebuilt by
    // SchemaFixupRunner. Adding a value requires updating both places.
    public static final String STATUS_PENDING = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REVISION = "REVISION_REQUESTED";

    private static final Duration PRESIGN_UPLOAD_TTL = Duration.ofMinutes(30);
    private static final Duration PRESIGN_DOWNLOAD_TTL = Duration.ofMinutes(30);

    private final InternEvaluationRepository evalRepo;
    private final DocumentRepository documentRepository;
    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final S3StorageService s3;
    private final UserNotificationDispatcher notifier;

    /** Max upload size in bytes — mirrors interview-recording default (2 GiB). */
    @Value("${app.evaluation.recording.max-bytes:2147483648}")
    private long maxBytes;

    // ── 1. Presign upload ────────────────────────────────────────────────

    @Transactional
    public EvaluationRecordingDtos.PresignUploadResponse presignUpload(
            UUID evaluationId,
            EvaluationRecordingDtos.PresignUploadRequest req,
            User caller) {
        if (req == null) throw new BadRequestException("body required");
        String fileName = req.fileName() == null ? "" : req.fileName().trim();
        String contentType = req.contentType() == null
                ? "" : req.contentType().trim().toLowerCase();
        Long fileSize = req.fileSize();

        if (fileName.isEmpty()) throw new BadRequestException("fileName required");
        if (!contentType.startsWith("video/")) {
            throw new BadRequestException(
                    "contentType must start with 'video/' (got '" + contentType + "')");
        }
        if (fileSize == null || fileSize <= 0L) {
            throw new BadRequestException("fileSize must be a positive number of bytes");
        }
        if (fileSize > maxBytes) {
            throw new BadRequestException(
                    "recording exceeds max size " + maxBytes + " bytes (got " + fileSize + ")");
        }
        if (!s3.isReady()) {
            throw new BadRequestException(
                    "S3 is not configured on this environment — set AWS_S3_* to enable "
                            + "presigned uploads.");
        }

        InternEvaluation ev = mustGet(evaluationId);
        requireEvaluatorOwnsOrSuperAdmin(ev, caller);

        // Key: evaluation-recordings/{internUserId}/{evaluationId}/{uuid}-{fileName}.
        // Grouping by intern first keeps per-intern bulk exports + audits
        // trivial (single S3 prefix walk).
        String storageKey = s3.key(
                "evaluation-recordings",
                ev.getInternId().toString(),
                ev.getId().toString(),
                UUID.randomUUID() + "-" + safeName(fileName));

        Document doc = Document.builder()
                .ownerUserId(caller.getId())
                .fileName(fileName)
                .fileSize(fileSize)
                .mimeType(contentType)
                .storageKey(storageKey)
                .category(CATEGORY)
                .sensitivity("NORMAL")
                .uploadedById(caller.getId())
                .build();
        doc = documentRepository.save(doc);

        String uploadUrl = s3.presignPutUrl(storageKey, contentType, PRESIGN_UPLOAD_TTL);
        Instant expiresAt = Instant.now().plus(PRESIGN_UPLOAD_TTL);

        log.info("[EvaluationRecording] presigned upload for eval={} docId={} sizeBytes={} type={}",
                evaluationId, doc.getId(), fileSize, contentType);

        return new EvaluationRecordingDtos.PresignUploadResponse(
                uploadUrl, doc.getId(), storageKey, expiresAt);
    }

    // ── 2. Save recording ref (+ optional project link) ──────────────────

    @Transactional
    public void saveRecording(
            UUID evaluationId,
            EvaluationRecordingDtos.SaveRecordingRequest req,
            User caller) {
        if (req == null || req.recordingDocumentId() == null) {
            throw new BadRequestException("recordingDocumentId required");
        }
        InternEvaluation ev = mustGet(evaluationId);
        requireEvaluatorOwnsOrSuperAdmin(ev, caller);
        // Terminal states are still allowed to attach/replace a recording —
        // catching a missed upload after publish is a common recovery path
        // and doesn't mutate any published fields.

        Document doc = documentRepository.findById(req.recordingDocumentId())
                .orElseThrow(() -> new BadRequestException(
                        "Document not found: " + req.recordingDocumentId()));
        if (doc.getDeletedAt() != null) {
            throw new BadRequestException("Document is no longer available.");
        }
        if (!CATEGORY.equals(doc.getCategory())) {
            throw new BadRequestException(
                    "Document is not an evaluation recording (category="
                            + doc.getCategory() + ")");
        }

        // Optional project link. If the evaluator picks a project, verify
        // the intern is actually assigned to it — prevents cross-intern
        // linkage bugs from a stale dropdown.
        if (req.linkedProjectId() != null) {
            boolean owns = projectAssignmentRepository
                    .findByInternIdOrderByAssignmentDateDescCreatedAtDesc(ev.getInternId())
                    .stream()
                    .anyMatch(a -> req.linkedProjectId().equals(a.getProjectId()));
            if (!owns) {
                throw new BadRequestException(
                        "Selected project is not on this intern's assignment list.");
            }
            ev.setLinkedProjectId(req.linkedProjectId());
        }

        // Group-scoped recording — a "Schedule Final" that covers two
        // projects should surface ONE recording upload that satisfies
        // both project cards. If this evaluation belongs to a
        // multi-member session group, stamp the recording (+ approval
        // reset + period backfill) on EVERY member so downstream
        // reads (gallery, cards, validations) see one uploaded artifact
        // group-wide. Single-project sessions (group-of-one or no
        // group) update only this row.
        List<InternEvaluation> updateTargets = ev.getSessionGroupId() != null
                ? evalRepo.findBySessionGroupId(ev.getSessionGroupId())
                : List.of(ev);

        LocalDate derivedPeriod = ev.getScheduledFor() != null
                ? ev.getScheduledFor().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                : (doc.getCreatedAt() != null
                        ? doc.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        : LocalDate.now(java.time.ZoneOffset.UTC));
        String scopeToApply = null;
        if (req.scope() != null && !req.scope().isBlank()) {
            String s = req.scope().trim().toUpperCase();
            if (!"P1".equals(s) && !"P2".equals(s) && !"P1_P2".equals(s)) {
                throw new BadRequestException(
                        "scope must be P1, P2, or P1_P2 (got '" + req.scope() + "')");
            }
            scopeToApply = s;
        }
        for (InternEvaluation target : updateTargets) {
            target.setRecordingDocumentId(doc.getId());
            // Safety net for the recording gallery month grouping. If a
            // row somehow reached upload with a null period_start
            // (legacy rows, or a POST_PROJECT auto-draft that was never
            // scheduled through the standard path), stamp one now so the
            // gallery groups the recording under a real month instead of
            // "unknown". Only stamp when null so a re-upload doesn't
            // rewrite an already-set period.
            if (target.getPeriodStart() == null) {
                target.setPeriodStart(derivedPeriod);
                if (target.getPeriodEnd() == null) target.setPeriodEnd(derivedPeriod);
            }
            // Any (re-)upload resets the gate to PENDING_APPROVAL for
            // every group member. Applies both to a fresh upload and a
            // re-upload after REVISION_REQUESTED: the manager gets one
            // approval decision that covers the whole session.
            target.setRecordingApprovalStatus(STATUS_PENDING);
            target.setRecordingRevisionNotes(null);
            target.setRecordingApprovedBy(null);
            target.setRecordingApprovedAt(null);
            if (scopeToApply != null) {
                target.setRecordingScope(scopeToApply);
            }
            evalRepo.save(target);
        }
        log.info("[EvaluationRecording] saved recording eval={} docId={} projectId={} "
                        + "scope={} groupId={} propagatedTo={} row(s) → PENDING_APPROVAL",
                evaluationId, doc.getId(), ev.getLinkedProjectId(),
                ev.getRecordingScope(), ev.getSessionGroupId(), updateTargets.size());

        // Fan out to every MANAGER — they own the approval queue. Best-effort;
        // any dispatcher error is logged, not propagated (the recording is
        // already saved and appears in the manager queue anyway).
        try {
            String internName = userRepository.findById(ev.getInternId())
                    .map(User::getFullName).orElse("intern");
            String actionUrl = "/careers/manager/recording-approvals";
            for (User mgr : userRepository.findByRole(UserRole.MANAGER)) {
                notifier.dispatch(
                        mgr.getId(),
                        "RECORDING_APPROVAL_PENDING",
                        ev.getInternId(),
                        "Recording awaiting approval: " + internName,
                        caller.getFullName() + " uploaded a session recording that needs review.",
                        actionUrl,
                        false);
            }
        } catch (Exception e) {
            log.warn("[EvaluationRecording] manager notify failed for eval={}: {}",
                    evaluationId, e.getMessage());
        }
    }

    // ── 3. Presign download (playback) ───────────────────────────────────

    /**
     * Streams a presigned GET URL suitable for an HTML5 {@code <video>}
     * tag. RBAC: the owning evaluator, ERM, MANAGER, or SUPER_ADMIN.
     * Returns null-safe when the recording is missing / the Document
     * was soft-deleted / S3 is unconfigured.
     */
    @Transactional(readOnly = true)
    public EvaluationRecordingDtos.DownloadUrlResponse presignDownload(
            UUID evaluationId, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        InternEvaluation ev = mustGet(evaluationId);
        requireCanViewRecording(ev, caller);
        UUID recDocId = ev.getRecordingDocumentId();
        if (recDocId == null) {
            throw new ResourceNotFoundException("No recording attached to this evaluation");
        }
        Document doc = documentRepository.findById(recDocId).orElse(null);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Recording document is no longer available");
        }
        if (!s3.isReady()) {
            throw new ConflictException(
                    "S3 is not configured on this environment — playback unavailable.");
        }
        String url = s3.presignGetUrl(doc.getStorageKey(), PRESIGN_DOWNLOAD_TTL);
        return new EvaluationRecordingDtos.DownloadUrlResponse(
                url, Instant.now().plus(PRESIGN_DOWNLOAD_TTL));
    }

    // ── 4. Gallery ───────────────────────────────────────────────────────

    /**
     * All evaluations that have a recording attached, joined with Document
     * + User + Project. Frontend groups by monthYear → intern → project.
     * RBAC gate is on the controller — service returns the same shape
     * regardless of caller role.
     */
    @Transactional(readOnly = true)
    public EvaluationRecordingDtos.GalleryResponse listGallery() {
        List<InternEvaluation> evals = evalRepo.findAll().stream()
                .filter(e -> e.getRecordingDocumentId() != null)
                .toList();
        if (evals.isEmpty()) {
            return new EvaluationRecordingDtos.GalleryResponse(List.of());
        }
        Set<UUID> docIds = new java.util.HashSet<>();
        Set<UUID> internIds = new java.util.HashSet<>();
        Set<UUID> projectIds = new java.util.HashSet<>();
        Set<UUID> evaluatorIds = new java.util.HashSet<>();
        for (InternEvaluation e : evals) {
            docIds.add(e.getRecordingDocumentId());
            internIds.add(e.getInternId());
            evaluatorIds.add(e.getEvaluatorId());
            if (e.getLinkedProjectId() != null) projectIds.add(e.getLinkedProjectId());
        }
        Map<UUID, Document> docs = new HashMap<>();
        for (Document d : documentRepository.findAllById(docIds)) {
            if (d.getDeletedAt() == null) docs.put(d.getId(), d);
        }
        Set<UUID> userIds = new java.util.HashSet<>();
        userIds.addAll(internIds);
        userIds.addAll(evaluatorIds);
        Map<UUID, User> users = new HashMap<>();
        for (User u : userRepository.findAllById(userIds)) users.put(u.getId(), u);
        Map<UUID, Project> projects = new HashMap<>();
        for (Project p : projectRepository.findAllById(projectIds)) {
            projects.put(p.getId(), p);
        }

        List<EvaluationRecordingDtos.RecordingRow> rows = new ArrayList<>(evals.size());
        for (InternEvaluation e : evals) {
            Document doc = docs.get(e.getRecordingDocumentId());
            if (doc == null) continue; // soft-deleted upstream
            User intern = users.get(e.getInternId());
            User evaluator = users.get(e.getEvaluatorId());
            Project project = e.getLinkedProjectId() != null
                    ? projects.get(e.getLinkedProjectId()) : null;
            LocalDate period = e.getPeriodStart();
            String monthYear = period != null
                    ? String.format("%d-%02d", period.getYear(), period.getMonthValue())
                    : "unknown";
            rows.add(new EvaluationRecordingDtos.RecordingRow(
                    e.getId(),
                    e.getRecordingDocumentId(),
                    monthYear,
                    period,
                    e.getPeriodEnd(),
                    e.getEvaluationType(),
                    e.getInternId(),
                    intern != null ? intern.getFullName() : null,
                    null, // employeeId — fetched below via lifecycle join if needed
                    e.getLinkedProjectId(),
                    project != null
                            ? (project.getName() != null ? project.getName() : project.getTitle())
                            : null,
                    e.getRecordingScope(),
                    e.getEvaluatorId(),
                    evaluator != null ? evaluator.getFullName() : null,
                    doc.getFileName(),
                    doc.getMimeType(),
                    doc.getFileSize(),
                    doc.getCreatedAt(),
                    e.getRecordingApprovalStatus(),
                    e.getRecordingRevisionNotes(),
                    e.getRecordingApprovedBy(),
                    e.getRecordingApprovedAt()));
        }
        rows.sort(
                Comparator.comparing(EvaluationRecordingDtos.RecordingRow::monthYear,
                                Comparator.reverseOrder())
                        .thenComparing(EvaluationRecordingDtos.RecordingRow::internName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(EvaluationRecordingDtos.RecordingRow::projectTitle,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return new EvaluationRecordingDtos.GalleryResponse(rows);
    }

    // ── 5. Intern-project selector (evaluator compose page) ──────────────

    /**
     * Projects assigned to the intern behind this evaluation, so the
     * compose page can render a dropdown for the evaluator to pick which
     * project the recording pertains to. Ordered most-recent-first.
     */
    @Transactional(readOnly = true)
    public List<EvaluationRecordingDtos.InternProjectOption> listInternProjects(
            UUID evaluationId, User caller) {
        InternEvaluation ev = mustGet(evaluationId);
        requireEvaluatorOwnsOrSuperAdmin(ev, caller);
        List<ProjectAssignment> assignments = projectAssignmentRepository
                .findByInternIdOrderByAssignmentDateDescCreatedAtDesc(ev.getInternId());
        if (assignments.isEmpty()) return List.of();
        Set<UUID> pids = new java.util.HashSet<>();
        for (ProjectAssignment a : assignments) pids.add(a.getProjectId());
        Map<UUID, Project> projects = new HashMap<>();
        for (Project p : projectRepository.findAllById(pids)) projects.put(p.getId(), p);
        List<EvaluationRecordingDtos.InternProjectOption> out = new ArrayList<>(assignments.size());
        for (ProjectAssignment a : assignments) {
            Project p = projects.get(a.getProjectId());
            String title = p != null
                    ? (p.getName() != null ? p.getName() : p.getTitle())
                    : "(unknown project)";
            out.add(new EvaluationRecordingDtos.InternProjectOption(
                    a.getProjectId(),
                    title,
                    a.getStatus() != null ? a.getStatus().name() : null,
                    a.getAssignmentDate(),
                    a.getDueDate()));
        }
        return out;
    }

    // ── Guards ───────────────────────────────────────────────────────────

    private InternEvaluation mustGet(UUID id) {
        return evalRepo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Evaluation not found: " + id));
    }

    private static void requireEvaluatorOwnsOrSuperAdmin(InternEvaluation ev, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (superAdmin) return;
        if (caller.getRoles() == null
                || !caller.getRoles().contains(UserRole.EVALUATOR)) {
            throw new ForbiddenException("EVALUATOR role required");
        }
        if (!caller.getId().equals(ev.getEvaluatorId())) {
            throw new ForbiddenException(
                    "You are not the evaluator on this evaluation.");
        }
    }

    private static void requireCanViewRecording(InternEvaluation ev, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        Set<UserRole> roles = caller.getRoles() == null
                ? java.util.Collections.emptySet() : caller.getRoles();
        // SUPER_ADMIN, ERM, and MANAGER see every recording at any status
        // (the approval queue and the ERM gallery both depend on this).
        if (roles.contains(UserRole.SUPER_ADMIN)
                || roles.contains(UserRole.ERM)
                || roles.contains(UserRole.MANAGER)) return;
        // Evaluator only sees their OWN recording, and only once the
        // manager has APPROVED it. PENDING_APPROVAL + REVISION_REQUESTED
        // stay hidden so the gate is meaningful.
        if (roles.contains(UserRole.EVALUATOR)
                && caller.getId().equals(ev.getEvaluatorId())) {
            if (STATUS_APPROVED.equals(ev.getRecordingApprovalStatus())) return;
            throw new ForbiddenException(
                    "Your recording is awaiting manager approval and is not "
                            + "yet available for playback.");
        }
        throw new ForbiddenException(
                "Not authorised to view this recording.");
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 100) cleaned = cleaned.substring(cleaned.length() - 100);
        return cleaned;
    }
}
