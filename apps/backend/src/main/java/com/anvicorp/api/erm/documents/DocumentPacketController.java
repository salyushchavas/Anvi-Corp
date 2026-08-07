package com.anvicorp.api.erm.documents;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.DocumentTaskRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.entity.DocumentTask;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ERM Phase 8 — document packets + review queue HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm")
@RequiredArgsConstructor
@Slf4j
public class DocumentPacketController {

    private final DocumentPacketService service;
    private final DocumentTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVaultService documentVault;

    // ── Packet list + detail + assign + admin actions ────────────────────

    @GetMapping("/document-packets")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.DocumentPacketListPage listPackets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        return service.listPackets(status, search, page, pageSize);
    }

    @GetMapping("/document-packets/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.DocumentPacketDetail get(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.getPacket(id, caller);
    }

    @GetMapping("/document-packets/by-lifecycle/{lifecycleId}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<DocumentDtos.DocumentPacketDetail> findActiveByLifecycle(
            @PathVariable UUID lifecycleId, @AuthenticationPrincipal User caller) {
        return service.findActiveForLifecycle(lifecycleId, caller)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/document-packets/assign")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.DocumentPacketDetail assign(
            @RequestBody DocumentDtos.AssignPacketRequest req,
            @AuthenticationPrincipal User caller) {
        return service.assignPacket(req, caller);
    }

    /**
     * ERM "assign additional / forgotten document" — layered on top of
     * an existing packet. Idempotent per (packet, documentKey): a doc
     * already on the packet is reopened (if closed) or just re-notified
     * (if in-flight); a new doc is created as a fresh PENDING task.
     * Reuses {@code DocumentPacketAssignedEvent} for the intern email +
     * in-app nudge.
     */
    @PostMapping("/document-packets/{id}/add-documents")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.DocumentPacketDetail addDocuments(
            @PathVariable UUID id,
            @RequestBody DocumentDtos.AddDocumentsRequest req,
            @AuthenticationPrincipal User caller) {
        return service.addDocumentsToPacket(id, req, caller);
    }

    @PostMapping("/document-packets/{id}/cancel")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public DocumentDtos.DocumentPacketDetail cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal User caller) {
        return service.cancelPacket(id, body == null ? null : body.get("reason"), caller);
    }

    @PostMapping("/document-packets/{id}/waive-pending")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public DocumentDtos.DocumentPacketDetail waivePending(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User caller) {
        return service.waivePendingTasks(id, body == null ? null : body.get("reason"), caller);
    }

    // ── Review queue + task detail + decision + bulk ─────────────────────

    @GetMapping("/document-review/queue")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.DocumentTaskListPage queue(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID internLifecycleId,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        // Past-month scroll filters review tasks by task.created_at (task
        // assigned in the month); current-month path is byte-identical to
        // the legacy overload.
        MonthRange range = MonthRange.parse(month);
        return service.listReviewQueue(
                category, search, internLifecycleId, page, pageSize, range);
    }

    /**
     * Person-first queue — one row per intern with documents awaiting
     * review. The per-document detail lives at
     * {@code /document-review/queue?internLifecycleId=…}.
     */
    @GetMapping("/document-review/queue/by-intern")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.InternReviewQueuePage queueByIntern(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        // Past-month scroll filters by packet.assigned_at; current-month
        // path is byte-identical to the legacy overload.
        MonthRange range = MonthRange.parse(month);
        return service.listReviewQueueByIntern(search, page, pageSize, range);
    }

    @GetMapping("/document-review/tasks/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.DocumentTaskDetail getTask(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.getTaskDetail(id, caller);
    }

    @GetMapping("/document-review/tasks/{id}/file")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> downloadUpload(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        DocumentTask t = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + id));
        if (t.getUploadedFileId() == null) {
            throw new ResourceNotFoundException("No upload for task " + id);
        }
        byte[] bytes = documentVault.readDocument(t.getUploadedFileId(), caller);
        Document meta = documentRepository.findById(t.getUploadedFileId()).orElse(null);
        String name = meta != null && meta.getFileName() != null
                ? meta.getFileName() : "upload";
        // Pass 2 verify-after-download gate — record the fetch on the row
        // so reviewTask() can later assert "this document has been pulled
        // for review at least once" before authorizing ACCEPT. Stamp is
        // best-effort: a DB hiccup here must NOT block the download
        // itself (the bytes are already read; the gate would just stay
        // closed until the next successful fetch).
        try {
            Integer cur = t.getDownloadCount();
            t.setDownloadCount((cur == null ? 0 : cur) + 1);
            t.setLastDownloadedAt(java.time.Instant.now());
            t.setDownloadedById(caller == null ? null : caller.getId());
            taskRepository.save(t);
        } catch (Exception e) {
            log.warn("[DocumentReview] download-stamp failed (non-fatal) "
                    + "for task {}: {}", id, e.getMessage());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    /**
     * Preview the submitted file INLINE. Deliberately does NOT stamp
     * {@code last_downloaded_at} / {@code download_count} /
     * {@code downloaded_by_id} — that stays exclusively on the /file
     * download endpoint above, which is the verify-after-download gate
     * that {@link DocumentPacketService#reviewTask reviewTask} checks
     * before allowing ACCEPT. Preview is for eyeballing (glance +
     * decide whether to fully download); the ERM must still commit to
     * a real download before they can Verify.
     *
     * <p>Response uses {@code Content-Disposition: inline} so browsers
     * embed the bytes rather than trigger the save dialog. The actual
     * MIME comes from the stored Document row so pdfs render as pdfs,
     * images as images, and everything else falls back to a generic
     * download prompt in the browser.</p>
     */
    @GetMapping("/document-review/tasks/{id}/preview")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> previewUpload(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        DocumentTask t = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + id));
        if (t.getUploadedFileId() == null) {
            throw new ResourceNotFoundException("No upload for task " + id);
        }
        byte[] bytes = documentVault.readDocument(t.getUploadedFileId(), caller);
        Document meta = documentRepository.findById(t.getUploadedFileId()).orElse(null);
        String name = meta != null && meta.getFileName() != null
                ? meta.getFileName() : "upload";
        String mime = meta != null && meta.getMimeType() != null
                ? meta.getMimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(mime))
                .body(bytes);
    }

    @PostMapping("/document-review/tasks/{id}/review")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentDtos.DocumentTaskDetail review(
            @PathVariable UUID id,
            @RequestBody DocumentDtos.ReviewTaskRequest req,
            @AuthenticationPrincipal User caller) {
        return service.reviewTask(id, req, caller);
    }

    @GetMapping("/document-review/reason-codes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<DocumentDtos.ReasonCodeGroup> reasonCodes() {
        return service.listReasonCodes();
    }
}
