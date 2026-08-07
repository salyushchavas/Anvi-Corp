package com.anvicorp.api.erm.documents;

import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplate;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplateRepository;
import com.anvicorp.api.entity.*;
import com.anvicorp.api.erm.documents.DocumentDtos.*;
import com.anvicorp.api.event.DocumentTaskSubmittedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ERM Phase 8.2 — intern-facing surface for the document packet
 * workflow. View the assigned packet + upload the filled, scanned-as-PDF
 * version. The blank template is served as a static Next.js asset at
 * {@code /document-templates/{filename}.pdf} — no backend download
 * endpoint exists for templates; the frontend builds the link from the
 * task's {@code documentKey}. All endpoints are scoped to the caller's
 * own lifecycle.
 *
 * <p>Upload is restricted to PDF only ({@code application/pdf}); the
 * intern is expected to print, fill by hand, and re-scan all pages into
 * a single PDF with their phone scanner app.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternDocumentService {

    /**
     * B2 — onboarding document uploads are capped at 2 MB. The tighter cap
     * only applies to this intern-facing onboarding surface (packet task
     * PDFs the intern re-scans after filling in). Resume uploads (via
     * ResumeController) and evaluation recording uploads (via
     * EvaluationRecordingController) keep their existing caps.
     */
    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;
    private static final String PDF_MIME = "application/pdf";
    private static final String PDF_REJECT_MSG =
            "Only PDF files are accepted. Please scan all filled pages into a single PDF "
            + "using your phone's scanner app (Adobe Scan, Microsoft Lens, Apple Notes, etc.).";
    private static final String SIZE_REJECT_MSG =
            "Upload exceeds 2 MB. Re-scan at a lower resolution or use your scanner "
            + "app's built-in size reduction, then try again.";

    private final DocumentPacketRepository packetRepository;
    private final DocumentTaskRepository taskRepository;
    private final DocumentTaskReviewLogRepository reviewLogRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVaultService documentVault;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentPacketService packetService;
    /** Admin-managed template resolver — returns the admin-uploaded
     *  S3 URL when present, else the legacy static enum URL, else
     *  null (upload-only docs). Wiring this in means the intern
     *  packet view actually surfaces admin-uploaded template files
     *  instead of always serving the seeded static asset. */
    private final com.anvicorp.api.admin.onboardingtemplates
            .OnboardingTemplateAdminService onboardingTemplateAdminService;
    /** Post-enum-widening — sensitivity / category come from the DB
     *  template row (enum-seeded or admin-added). Keeps the upload
     *  vault tagging correct for BOTH enum + custom keys. */
    private final OnboardingDocumentTemplateRepository templateRepository;

    // ── Read ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<InternPacketView> getMyPacket(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        InternLifecycle lc = lifecycleRepository.findByUserId(caller.getId()).orElse(null);
        if (lc == null) return Optional.empty();
        Optional<DocumentPacket> active = packetRepository.findActiveByLifecycle(lc.getId());
        if (active.isEmpty()) return Optional.empty();
        return Optional.of(toInternPacketView(active.get()));
    }

    // ── Upload filled file ───────────────────────────────────────────────

    @Transactional
    public InternTaskView uploadFilled(UUID taskId, MultipartFile file, User caller) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException(SIZE_REJECT_MSG);
        }
        // ERM Phase 8.2 — strict PDF gate. Some browsers leave the MIME
        // null/empty on slow uploads; we still require the filename to
        // end in .pdf when no MIME is supplied.
        String mime = file.getContentType();
        String filename = file.getOriginalFilename();
        boolean mimeOk = PDF_MIME.equalsIgnoreCase(mime);
        boolean filenameOk = filename != null
                && filename.toLowerCase().endsWith(".pdf");
        if (!mimeOk && !(mime == null && filenameOk)) {
            throw new BadRequestException(PDF_REJECT_MSG);
        }

        DocumentTask t = mustLoadOwnTask(taskId, caller);
        if (Set.of("ACCEPTED", "WAIVED").contains(t.getStatus())) {
            throw new ConflictException(
                    "Task is " + t.getStatus() + "; cannot upload");
        }
        // Phase 1.6 — block uploads while the packet is locked. An ERM
        // REJECT / RESEND_REQUEST clears the lock so the intern can
        // re-upload the affected task(s) and re-submit. Locked is a
        // packet-wide flag (not per-task) because the intern's "I'm
        // done" gesture is whole-packet by design.
        DocumentPacket pk = packetRepository.findById(t.getPacketId()).orElse(null);
        if (pk != null && Boolean.TRUE.equals(pk.getInternLocked())) {
            throw new ConflictException(
                    "Packet was submitted to ERM and is locked for review. "
                            + "Wait for ERM to reject a document or contact them to reopen.");
        }

        String key = t.getDocumentKey();
        OnboardingDocumentTemplate doc = key == null ? null
                : templateRepository.findByKey(key).orElse(null);
        String sensitivity = doc != null ? doc.getSensitivity() : "GENERAL";
        String category = doc != null ? doc.getCategory() : "OTHER";

        try {
            byte[] bytes = file.getBytes();
            Document saved = documentVault.saveDocument(
                    caller.getId(),
                    filename != null ? filename : "filled.pdf",
                    PDF_MIME,
                    bytes,
                    category,
                    sensitivity,
                    caller.getId());
            String previous = t.getStatus();
            // Pure-overwrite on revision re-upload — the previous file
            // (if any) is soft-deleted so only the latest version is
            // visible from the gallery / vault list. Bytes in S3/disk
            // are not eagerly removed; the soft-delete sets deleted_at
            // on the Document row, and every read path filters that
            // out. Best-effort: failure to soft-delete the prior row
            // logs at WARN — the new file is already saved and the
            // pointer swap below succeeds either way.
            UUID previousFileId = t.getUploadedFileId();
            if (previousFileId != null && !previousFileId.equals(saved.getId())) {
                try {
                    documentRepository.findById(previousFileId).ifPresent(prior -> {
                        if (prior.getDeletedAt() == null) {
                            prior.setDeletedAt(Instant.now());
                            documentRepository.save(prior);
                        }
                    });
                } catch (Exception e) {
                    log.warn("[InternDocument] prior-file soft-delete failed "
                            + "(non-fatal) for task {} previous file {}: {}",
                            t.getId(), previousFileId, e.getMessage());
                }
            }
            t.setUploadedFileId(saved.getId());
            t.setStatus("SUBMITTED");
            t.setSubmittedAt(Instant.now());
            // Clear any prior reviewer comments so the new round starts clean.
            // (Comments stay in audit log via review log entries.)
            t.setReviewedAt(null);
            t.setReviewedById(null);
            t.setReviewReasonCode(null);
            t.setReviewComments(null);
            // ERM Pass 2 verify-after-download gate — reset the download
            // stamp in the SAME transaction as the file swap. Without
            // this, the stale stamp from the PREVIOUS file lets an ERM
            // ACCEPT the resubmission without ever opening the new bytes
            // (the ACCEPT branch of DocumentPacketService.reviewTask gates
            // on `lastDownloadedAt != null` only, with no version check).
            t.setLastDownloadedAt(null);
            t.setDownloadedById(null);
            t.setDownloadCount(0);
            DocumentTask savedTask = taskRepository.save(t);

            try {
                reviewLogRepository.save(DocumentTaskReviewLog.builder()
                        .taskId(savedTask.getId())
                        .actorUserId(caller.getId())
                        .eventType("INTERN_UPLOADED")
                        .previousStatus(previous)
                        .newStatus("SUBMITTED")
                        .build());
            } catch (Exception ignored) {}

            // Trigger packet-status side-effects (ASSIGNED → IN_PROGRESS,
            // pending → ALL_SUBMITTED when last one comes in, etc.).
            packetService.checkPacketCompletion(savedTask.getPacketId(), caller);

            // Notify ERM.
            UUID lifecycleId = packetRepository.findById(savedTask.getPacketId())
                    .map(DocumentPacket::getInternLifecycleId).orElse(null);
            String templateTitle = doc != null ? doc.getTitle() : "(unknown)";
            try {
                eventPublisher.publishEvent(new DocumentTaskSubmittedEvent(
                        savedTask.getId(), savedTask.getPacketId(),
                        lifecycleId, caller.getId(), templateTitle));
            } catch (Exception e) {
                log.warn("[InternDocument] submitted event publish failed: {}",
                        e.getMessage());
            }
            return toInternTaskView(savedTask);
        } catch (BadRequestException | ConflictException | ForbiddenException re) {
            throw re;
        } catch (Exception e) {
            log.warn("[InternDocument] upload failed: {}", e.getMessage());
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    // ── Submit all documents to ERM (Phase 1.6) ──────────────────────────

    /**
     * Explicit intern handoff: lock the packet, stamp the timestamp, and
     * let {@code checkPacketCompletion} auto-flip the packet status to
     * ALL_SUBMITTED (it was likely there already once the last task was
     * uploaded; the call is idempotent).
     *
     * <p>Server-enforced gate: the caller must own the packet AND every
     * task on it must be out of PENDING. Activation is NOT triggered —
     * only ERM ACCEPT advances the lifecycle.</p>
     */
    @Transactional
    public InternPacketView submitToErm(UUID packetId, User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        DocumentPacket pk = packetRepository.findById(packetId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Packet not found: " + packetId));
        InternLifecycle lc = lifecycleRepository.findById(pk.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle missing"));
        if (!caller.getId().equals(lc.getUserId())) {
            throw new ForbiddenException("Packet does not belong to caller");
        }
        if ("CANCELLED".equals(pk.getStatus())) {
            throw new ConflictException("Packet has been cancelled");
        }
        if ("COMPLETED".equals(pk.getStatus())) {
            throw new ConflictException(
                    "Packet has already been verified by ERM — nothing to submit");
        }
        if (Boolean.TRUE.equals(pk.getInternLocked())) {
            // Idempotent: a second click after the first success just
            // re-returns the locked state, not a 409. Avoids confusing
            // the intern if they double-click.
            return toInternPacketView(pk);
        }
        long pending = taskRepository.countByPacketIdAndStatus(packetId, "PENDING");
        if (pending > 0) {
            throw new BadRequestException(
                    pending + " required document(s) still need to be uploaded "
                            + "before you can submit.");
        }

        pk.setInternLocked(Boolean.TRUE);
        pk.setInternSubmittedAt(Instant.now());
        packetRepository.save(pk);
        // Auto-promote packet status to ALL_SUBMITTED if not already
        // there (every task is non-PENDING by the gate above).
        try {
            packetService.checkPacketCompletion(packetId, caller);
        } catch (Exception e) {
            log.warn("[InternDocument] checkPacketCompletion on submit failed: {}",
                    e.getMessage());
        }
        // Re-read so the response carries the new packet status set by
        // checkPacketCompletion (idempotent if already ALL_SUBMITTED).
        DocumentPacket fresh = packetRepository.findById(packetId).orElse(pk);
        return toInternPacketView(fresh);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DocumentTask mustLoadOwnTask(UUID taskId, User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        DocumentTask t = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Task not found: " + taskId));
        DocumentPacket pk = packetRepository.findById(t.getPacketId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Packet missing for task " + taskId));
        InternLifecycle lc = lifecycleRepository.findById(pk.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle missing"));
        if (!caller.getId().equals(lc.getUserId())) {
            throw new ForbiddenException(
                    "Task does not belong to caller");
        }
        return t;
    }

    private InternPacketView toInternPacketView(DocumentPacket pk) {
        List<InternTaskView> tasks = new ArrayList<>();
        int accepted = 0;
        int pending = 0;
        for (DocumentTask t : taskRepository.findByPacketIdOrderByCreatedAtAsc(pk.getId())) {
            tasks.add(toInternTaskView(t));
            if ("ACCEPTED".equals(t.getStatus()) || "WAIVED".equals(t.getStatus())) {
                accepted++;
            } else if ("PENDING".equals(t.getStatus())) {
                pending++;
            }
        }
        return new InternPacketView(
                pk.getId(), pk.getStatus(), pk.getCustomInstructions(),
                pk.getAssignedAt(), pk.getCompletedAt(),
                tasks, tasks.size(), accepted,
                Boolean.TRUE.equals(pk.getInternLocked()),
                pk.getInternSubmittedAt(),
                pending);
    }

    private InternTaskView toInternTaskView(DocumentTask t) {
        // Post-enum-widening — the key is a String (enum-seeded or
        // admin-added custom). Resolve title / category / sensitivity
        // from the {@code onboarding_document_templates} table which
        // covers BOTH cases; fall through to the raw key if the row was
        // deleted out-of-band so legacy tasks still surface.
        String key = t.getDocumentKey();
        OnboardingDocumentTemplate d = key == null ? null
                : templateRepository.findByKey(key).orElse(null);
        // Route through the admin resolver so admin-uploaded S3 files
        // win over the legacy static enum asset, and custom keys also
        // resolve to their uploaded blank PDF (or null for upload-only).
        String templateUrl = key != null
                ? onboardingTemplateAdminService.resolveDownloadUrlOrNull(key)
                : null;
        return new InternTaskView(
                t.getId(),
                key,
                d != null ? d.getTitle() : (key != null ? key : "(unknown)"),
                d != null ? d.getDescription() : null,
                d != null ? d.getCategory() : null,
                d != null ? d.getSensitivity() : null,
                templateUrl,
                t.getStatus(), t.getVersion(),
                t.getTaskInstructions(),
                t.getSubmittedAt(), t.getReviewedAt(),
                t.getReviewReasonCode(),
                t.getReviewComments(),
                null);
    }
}
