package com.anvicorp.api.erm.documents;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ERM Document Gallery HTTP surface — a per-intern catalogue of every
 * onboarding document uploaded so far. Read-only on top of the existing
 * document_packets / document_tasks / documents tables. Download path
 * reuses {@code GET /api/v1/erm/document-review/tasks/{taskId}/file} —
 * both endpoints are gated to {@code ERM} / {@code SUPER_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/erm/document-gallery")
@RequiredArgsConstructor
public class DocumentGalleryController {

    private final DocumentGalleryService service;

    @GetMapping("/interns")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentGalleryDtos.InternListResponse listInterns(
            @RequestParam(required = false, defaultValue = "ALL") String status,
            @RequestParam(required = false) String search) {
        return service.listInterns(status, search);
    }

    @GetMapping("/interns/{lifecycleId}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentGalleryDtos.InternGalleryDetail getInternDetail(
            @PathVariable UUID lifecycleId) {
        return service.getInternDetail(lifecycleId);
    }
}
