package com.anvicorp.api.workspace.api;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.workspace.api.dto.ReturnSubmissionRequest;
import com.anvicorp.api.workspace.api.dto.SubmissionResponse;
import com.anvicorp.api.workspace.api.dto.WorkspaceFileResponse;
import com.anvicorp.api.workspace.application.SubmissionService;
import com.anvicorp.api.workspace.domain.WorkspaceSubmission;
import com.anvicorp.api.workspace.domain.WorkspaceSubmittedFile;
import com.anvicorp.api.workspace.infrastructure.WorkspaceSubmittedFileRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Submission lifecycle endpoints — staff-side only after the Phase-0
 * intern-surface cleanup. The intern-callable {@code POST /workspace/submit}
 * route is removed (Monaco editor + its submission flow retired); the
 * remaining read endpoints are tightened to TRAINER / REPORTING_MANAGER /
 * SUPER_ADMIN so reviewers can still open prior submissions during the
 * deprecation window.
 */
@RestController
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;
    private final WorkspaceSubmittedFileRepository submittedFileRepository;

    @GetMapping("/api/v1/projects/{projectId}/submissions")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public List<SubmissionResponse> listForProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal User caller) {
        return submissionService.listForProject(projectId, caller).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/api/v1/submissions/{submissionId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public SubmissionDetailResponse get(
            @PathVariable UUID submissionId,
            @AuthenticationPrincipal User caller) {
        WorkspaceSubmission s = submissionService.getSubmission(submissionId, caller);
        List<WorkspaceSubmittedFile> files =
                submissionService.getSubmissionFiles(submissionId, caller);
        List<WorkspaceFileResponse> fileDtos = files.stream()
                .map(f -> new WorkspaceFileResponse(
                        f.getId(), s.getProjectId(), f.getPath(),
                        f.getSizeBytes(), null, null, null))
                .toList();
        return new SubmissionDetailResponse(toResponse(s), fileDtos);
    }

    @GetMapping("/api/v1/submissions/{submissionId}/files/**")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public WorkspaceFileResponse getFile(
            @PathVariable UUID submissionId,
            HttpServletRequest request,
            @AuthenticationPrincipal User caller) {
        String path = extractPath(request, submissionId);
        WorkspaceSubmittedFile f = submissionService.getSubmissionFile(submissionId, path, caller);
        return new WorkspaceFileResponse(
                f.getId(), null, f.getPath(),
                f.getSizeBytes(), null, null, f.getContent());
    }

    @PostMapping("/api/v1/submissions/{submissionId}/approve")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public SubmissionResponse approve(
            @PathVariable UUID submissionId,
            @AuthenticationPrincipal User caller) {
        return toResponse(submissionService.approveTechnically(submissionId, caller));
    }

    @PostMapping("/api/v1/submissions/{submissionId}/return")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public SubmissionResponse returnForRevisions(
            @PathVariable UUID submissionId,
            @Valid @RequestBody ReturnSubmissionRequest body,
            @AuthenticationPrincipal User caller) {
        return toResponse(submissionService.returnForRevisions(
                submissionId, caller, body.reason()));
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private SubmissionResponse toResponse(WorkspaceSubmission s) {
        int fileCount = submittedFileRepository
                .findBySubmissionIdOrderByPathAsc(s.getId()).size();
        return new SubmissionResponse(
                s.getId(),
                s.getProjectId(),
                s.getSubmissionNumber(),
                s.getSubmittedAt(),
                s.getSubmittedBy(),
                s.getReviewedAt(),
                s.getReviewerId(),
                s.getReviewOutcome() != null ? s.getReviewOutcome().name() : null,
                s.getReviewReason(),
                fileCount);
    }

    private static String extractPath(HttpServletRequest request, UUID submissionId) {
        String uri = request.getRequestURI();
        String prefix = "/api/v1/submissions/" + submissionId + "/files/";
        int idx = uri.indexOf(prefix);
        if (idx < 0) return "";
        String raw = uri.substring(idx + prefix.length());
        return UriUtils.decode(raw, StandardCharsets.UTF_8);
    }

    /** Submission metadata + the file index (paths + sizes, no content). */
    public record SubmissionDetailResponse(
            SubmissionResponse submission,
            List<WorkspaceFileResponse> files
    ) {}
}
