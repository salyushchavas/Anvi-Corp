package com.anvicorp.api.evaluator.recordings;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTTP surface for the evaluator session-recording flow.
 *
 * <p>Evaluator-only endpoints (upload / save / list-projects) sit under
 * {@code /api/v1/evaluator/evaluations/{id}/recording*}. The gallery
 * endpoints ERM + MANAGER consume live under
 * {@code /api/v1/evaluation-recordings/*}. Playback (download-url)
 * accepts EVALUATOR + ERM + MANAGER + SUPER_ADMIN — the service
 * additionally scopes the EVALUATOR variant to the owning evaluator.</p>
 */
@RestController
@RequiredArgsConstructor
public class EvaluationRecordingController {

    private final EvaluationRecordingService service;

    // ── Evaluator-side ────────────────────────────────────────────────────

    @PostMapping("/api/v1/evaluator/evaluations/{id}/recording/presign-upload")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public EvaluationRecordingDtos.PresignUploadResponse presignUpload(
            @PathVariable UUID id,
            @RequestBody EvaluationRecordingDtos.PresignUploadRequest req,
            @AuthenticationPrincipal User caller) {
        return service.presignUpload(id, req, caller);
    }

    @PostMapping("/api/v1/evaluator/evaluations/{id}/recording")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public ResponseEntity<Void> saveRecording(
            @PathVariable UUID id,
            @RequestBody EvaluationRecordingDtos.SaveRecordingRequest req,
            @AuthenticationPrincipal User caller) {
        service.saveRecording(id, req, caller);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/evaluator/evaluations/{id}/intern-projects")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public List<EvaluationRecordingDtos.InternProjectOption> internProjects(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return service.listInternProjects(id, caller);
    }

    // ── Gallery (ERM + Manager) ───────────────────────────────────────────

    @GetMapping("/api/v1/evaluation-recordings")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'SUPER_ADMIN')")
    public EvaluationRecordingDtos.GalleryResponse list() {
        return service.listGallery();
    }

    /**
     * Playback presigned GET URL. RBAC accepts the owning evaluator too
     * so the compose page can preview the recording it just uploaded
     * from the same endpoint — service scopes the check.
     */
    @GetMapping("/api/v1/evaluation-recordings/{evaluationId}/download-url")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'ERM', 'MANAGER', 'SUPER_ADMIN')")
    public EvaluationRecordingDtos.DownloadUrlResponse downloadUrl(
            @PathVariable UUID evaluationId,
            @AuthenticationPrincipal User caller) {
        return service.presignDownload(evaluationId, caller);
    }
}
