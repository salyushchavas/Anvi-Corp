package com.anvicorp.api.recordings;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.evaluator.recordings.EvaluationRecordingDtos;
import com.anvicorp.api.evaluator.recordings.EvaluationRecordingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Unified Recording Gallery + standalone-upload HTTP surface. Combines
 * the three role-scoped gallery pages (ERM / Manager / Evaluator) and
 * the two dedicated upload pages (ERM interview / Evaluator
 * evaluation) into one file — the gallery response is filtered
 * server-side by role so a single endpoint serves all three UIs. RBAC
 * is enforced both at the controller (blanket) and inside the service
 * (per-role filtering).
 */
@RestController
@RequiredArgsConstructor
public class RecordingGalleryController {

    private final RecordingGalleryService galleryService;
    private final ErmStandaloneRecordingService ermStandaloneService;
    private final EvaluatorStandaloneUploadService evaluatorStandaloneService;
    private final EvaluationRecordingService evalRecordingService;

    // ── Unified gallery ──────────────────────────────────────────────

    @GetMapping("/api/v1/recording-gallery")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'EVALUATOR', 'SUPER_ADMIN')")
    public RecordingGalleryDtos.GalleryResponse gallery(
            @AuthenticationPrincipal User caller) {
        return galleryService.buildTree(caller);
    }

    // ── ERM standalone interview upload ──────────────────────────────

    @PostMapping("/api/v1/erm/upload-interview-recording/presign")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public StandaloneRecordingDtos.PresignResponse presignInterviewUpload(
            @RequestBody StandaloneRecordingDtos.InterviewPresignRequest req,
            @AuthenticationPrincipal User caller) {
        return ermStandaloneService.presignInterviewUpload(req, caller);
    }

    /** Playback for a standalone interview recording — the gallery's
     *  {@code downloadUrlPath} points here when the file didn't come
     *  from a scorecard-flow Interview row. */
    @GetMapping("/api/v1/interview-recordings/by-document/{documentId}/download-url")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'SUPER_ADMIN')")
    public StandaloneRecordingDtos.DownloadUrlResponse presignStandaloneInterviewDownload(
            @PathVariable UUID documentId) {
        return ermStandaloneService.presignInterviewDownload(documentId);
    }

    // ── Evaluator standalone eval upload ─────────────────────────────

    /** Pre-flight: resolve (or create) the target InternEvaluation and
     *  auto-retrieve the project title. The frontend then hits the
     *  existing evaluator presign endpoint against the returned
     *  {@code evaluationId} — the approval gate applies as usual. */
    @PostMapping("/api/v1/evaluator/upload-recording/prepare")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'SUPER_ADMIN')")
    public StandaloneRecordingDtos.EvaluatorPrepareResponse prepareEvaluatorUpload(
            @RequestBody StandaloneRecordingDtos.EvaluatorPrepareRequest req,
            @AuthenticationPrincipal User caller) {
        return evaluatorStandaloneService.prepare(req, caller);
    }

    /** Active intern picker — used by both the ERM and evaluator upload
     *  screens. Doubly-guarded RBAC keeps the intern list from leaking
     *  to any role that shouldn't see it. */
    @GetMapping("/api/v1/recording-gallery/active-interns")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'EVALUATOR', 'SUPER_ADMIN')")
    public List<StandaloneRecordingDtos.ActiveEvalueeOption> listActiveInterns() {
        return evaluatorStandaloneService.listActiveInterns();
    }

    // ── Evaluation-recording gallery (legacy — kept for back-compat) ──

    /** Preserved for the existing ERM + Manager evaluation-recordings
     *  page. Delegates straight to the shipped service; the newer
     *  unified {@link #gallery} endpoint above supersedes it and adds
     *  interview recordings + role-based visibility. */
    @GetMapping("/api/v1/evaluation-recordings/legacy")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'SUPER_ADMIN')")
    public EvaluationRecordingDtos.GalleryResponse legacyEvalGallery() {
        return evalRecordingService.listGallery();
    }
}
