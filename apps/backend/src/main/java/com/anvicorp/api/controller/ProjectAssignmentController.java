package com.anvicorp.api.controller;

import com.anvicorp.api.dto.project.catalog.AssignProjectRequest;
import com.anvicorp.api.dto.project.catalog.AssignProjectResultResponse;
import com.anvicorp.api.dto.project.catalog.EligibleInternResponse;
import com.anvicorp.api.dto.project.catalog.ProjectAssignmentResponse;
import com.anvicorp.api.dto.project.catalog.ReturnAssignmentRequest;
import com.anvicorp.api.dto.project.catalog.SubmitAssignmentRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ProjectAssignmentStatus;
import com.anvicorp.api.service.ProjectAssignmentService;
import com.anvicorp.api.service.ProjectAssignmentService.DownloadedProjectFile;
import com.anvicorp.api.service.ProjectAssignmentService.SubmissionAttachmentDownload;
import com.anvicorp.api.service.ProjectAssignmentService.SubmissionAttachmentUploadResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/project-assignments")
@RequiredArgsConstructor
public class ProjectAssignmentController {

    private final ProjectAssignmentService service;
    private final com.anvicorp.api.auth.RegistrationRateLimiter rateLimiter;

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public AssignProjectResultResponse assign(
            @Valid @RequestBody AssignProjectRequest req,
            @AuthenticationPrincipal User caller) {
        return service.assignToInterns(req, caller);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('INTERN')")
    public ProjectAssignmentResponse start(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.startAssignment(id, caller);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('INTERN')")
    public ProjectAssignmentResponse submit(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) SubmitAssignmentRequest req,
            @AuthenticationPrincipal User caller) {
        String notes = req != null ? req.submissionNotes() : null;
        java.util.List<String> links = req != null ? req.deliverableLinks() : null;
        UUID attachmentId = req != null ? req.attachmentDocumentId() : null;
        return service.submitAssignment(id, notes, links, attachmentId, caller);
    }

    /**
     * Upload an optional supporting file for the intern's submission.
     * Returns the {@code documentId} the intern must then send in the
     * follow-up {@code POST /submit} body as {@code attachmentDocumentId}.
     * Two-step to keep the {@code /submit} endpoint pure-JSON and reuse
     * the document vault's storage pipeline.
     */
    @PostMapping(
            value = "/{id}/submission-attachment",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('INTERN')")
    public SubmissionAttachmentUploadResponse uploadSubmissionAttachment(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User caller) {
        // Security Wave 3 — per-user upload throttle (10/user/min).
        rateLimiter.enforceAuthenticatedUpload(caller == null ? null : caller.getId());
        return service.uploadSubmissionAttachment(id, file, caller);
    }

    /**
     * Download an intern-uploaded submission attachment. RBAC gates on
     * the assignment's intern owner + assigned trainer + SUPER_ADMIN
     * (same shape as the trainer-project-file download above).
     */
    @GetMapping("/submissions/{submissionId}/attachment")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN', 'INTERN')")
    public ResponseEntity<byte[]> downloadSubmissionAttachment(
            @PathVariable UUID submissionId,
            @AuthenticationPrincipal User caller) {
        SubmissionAttachmentDownload f = service.downloadSubmissionAttachment(submissionId, caller);
        String mime = f.mimeType() != null ? f.mimeType() : "application/octet-stream";
        String safeName = sanitizeFilename(f.fileName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\"")
                .contentType(MediaType.parseMediaType(mime))
                .contentLength(f.bytes().length)
                .body(f.bytes());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('INTERN')")
    public List<ProjectAssignmentResponse> mine(@AuthenticationPrincipal User caller) {
        return service.listForIntern(caller.getId());
    }

    @GetMapping("/by-project/{projectId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<ProjectAssignmentResponse> byProject(@PathVariable UUID projectId) {
        return service.listForProject(projectId);
    }

    @GetMapping("/eligible-interns")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<EligibleInternResponse> eligibleInterns() {
        return service.eligibleInterns();
    }

    // ── Reviewer lifecycle (TE + RM) ───────────────────────────────────────

    @PostMapping("/{id}/tech-approve")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ProjectAssignmentResponse techApprove(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.techApprove(id, caller);
    }

    @PostMapping("/{id}/return-revisions")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public ProjectAssignmentResponse returnForRevisions(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnAssignmentRequest req,
            @AuthenticationPrincipal User caller) {
        return service.returnForRevisions(id, req.reason(), caller);
    }

    @PostMapping("/{id}/mark-pending-viva")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public ProjectAssignmentResponse markPendingViva(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.markPendingViva(id, caller);
    }

    @PostMapping("/{id}/complete-after-viva")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public ProjectAssignmentResponse completeAfterViva(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.completeAfterViva(id, caller);
    }

    /**
     * Status-filtered roster. TE uses {@code ?status=SUBMITTED} for the
     * tech-review queue; RM uses {@code ?status=TECH_APPROVED&status=PENDING_VIVA}
     * for the viva queue. Both roles may read either filter — the dashboards
     * decide which slice to show.
     */
    @GetMapping("/by-status")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public List<ProjectAssignmentResponse> byStatus(
            @RequestParam("status") List<ProjectAssignmentStatus> statuses) {
        return service.listByStatuses(statuses);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN', 'INTERN')")
    public ProjectAssignmentResponse get(@PathVariable UUID id,
                                          @AuthenticationPrincipal User caller) {
        return service.getAssignment(id, caller);
    }

    /**
     * Download a trainer-uploaded project file (brief / starter zip / spec).
     * Authorized on the assignment row: the owning intern, the trainer, or
     * SUPER_ADMIN. The Document Vault's own RBAC would reject the intern
     * because the file is owned by the trainer who uploaded it — this
     * endpoint substitutes assignment-ownership as the access policy.
     *
     * <p>{@code documentId} is optional; when omitted the first attached
     * file is returned (currently the only one — the underlying
     * resource_links_json carries a single entry). Passing it lets future
     * multi-file UI request a specific attachment by id while still
     * being scope-checked against this assignment's project.</p>
     */
    @GetMapping("/{id}/file")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN', 'INTERN')")
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable UUID id,
            @RequestParam(value = "documentId", required = false) UUID documentId,
            @AuthenticationPrincipal User caller) {
        DownloadedProjectFile f = service.downloadProjectFile(id, documentId, caller);
        String mime = f.mimeType() != null ? f.mimeType() : "application/octet-stream";
        String safeName = sanitizeFilename(f.fileName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\"")
                .contentType(MediaType.parseMediaType(mime))
                .contentLength(f.bytes().length)
                .body(f.bytes());
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "project-file.bin";
        return name.replaceAll("[\\r\\n\"\\\\]", "_");
    }
}
