package com.anvicorp.api.manager.recordings;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Manager approval queue for evaluation recordings. Playback URLs are
 * fetched via the existing
 * {@code /api/v1/evaluation-recordings/{evaluationId}/download-url}
 * endpoint (RBAC already grants MANAGER), so no duplicate playback
 * surface here — this controller only owns the queue + state
 * transitions.
 */
@RestController
@RequestMapping("/api/v1/manager/recording-approvals")
@RequiredArgsConstructor
public class ManagerRecordingApprovalController {

    private final ManagerRecordingApprovalService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerRecordingApprovalDtos.PendingListResponse list() {
        return service.listPending();
    }

    @PostMapping("/{evaluationId}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<Void> approve(
            @PathVariable UUID evaluationId,
            @AuthenticationPrincipal User caller) {
        service.approve(evaluationId, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{evaluationId}/request-revision")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<Void> requestRevision(
            @PathVariable UUID evaluationId,
            @RequestBody ManagerRecordingApprovalDtos.RequestRevisionRequest req,
            @AuthenticationPrincipal User caller) {
        service.requestRevision(evaluationId, req, caller);
        return ResponseEntity.noContent().build();
    }
}
