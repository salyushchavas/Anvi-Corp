package com.anvicorp.api.admin.onboardingtemplates;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin ⟶ Onboarding Documents HTTP surface. RBAC gates every
 * write path to SUPER_ADMIN + JOBS_ADMIN; the intern-side resolve
 * endpoint at the bottom is intentionally open to authenticated
 * interns + staff so packet tasks can render their download button.
 */
@RestController
@RequiredArgsConstructor
public class OnboardingTemplateAdminController {

    private final OnboardingTemplateAdminService service;

    // ── Admin CRUD ────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/onboarding-templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public OnboardingTemplateDtos.TemplateListResponse list() {
        return service.list();
    }

    @PostMapping("/api/v1/admin/onboarding-templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public OnboardingTemplateDtos.TemplateRow create(
            @RequestBody OnboardingTemplateDtos.CreateTemplateRequest req) {
        return service.create(req);
    }

    @PatchMapping("/api/v1/admin/onboarding-templates/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public OnboardingTemplateDtos.TemplateRow update(
            @PathVariable UUID id,
            @RequestBody OnboardingTemplateDtos.UpdateTemplateMetadataRequest req) {
        return service.updateMetadata(id, req);
    }

    @PostMapping("/api/v1/admin/onboarding-templates/{id}/file/presign-upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public OnboardingTemplateDtos.PresignUploadResponse presignUpload(
            @PathVariable UUID id,
            @RequestBody OnboardingTemplateDtos.PresignUploadRequest req,
            @AuthenticationPrincipal User caller) {
        return service.presignUpload(id, req, caller);
    }

    @PostMapping("/api/v1/admin/onboarding-templates/{id}/file")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public OnboardingTemplateDtos.TemplateRow attachFile(
            @PathVariable UUID id,
            @RequestBody OnboardingTemplateDtos.AttachFileRequest req) {
        return service.attachFile(id, req);
    }

    @DeleteMapping("/api/v1/admin/onboarding-templates/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Intern-side resolution ───────────────────────────────────

    /**
     * Resolve the current blank-template download URL for a template
     * key. Returns an S3 presigned URL when the admin has uploaded a
     * replacement file, else falls back to the legacy static asset URL
     * from the {@code SkyzenDocument} enum. Available to any
     * authenticated user so the intern packet view + the ERM /
     * Manager / Evaluator gallery UIs can render the download link
     * without a role-specific endpoint per surface.
     */
    @GetMapping("/api/v1/onboarding-templates/{key}/download-url")
    @PreAuthorize("isAuthenticated()")
    public OnboardingTemplateDtos.DownloadResolutionResponse resolveDownload(
            @PathVariable String key) {
        return service.resolveDownload(key);
    }

    /**
     * ERM-facing pickable-templates list — every ACTIVE row from
     * {@code onboarding_document_templates}, seeded from the
     * SkyzenDocument enum AND augmented with admin-added rows.
     * Feeds the assignment modals (Assign Packet + Assign Additional
     * Document) so admin-created templates actually appear alongside
     * the enum-derived set. Delegates straight to
     * {@link OnboardingTemplateAdminService#list} and derives the
     * lighter picker shape here so callers don't have to filter or
     * strip fields client-side.
     */
    @GetMapping("/api/v1/erm/onboarding-templates/pickable")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public OnboardingTemplateDtos.PickableTemplateListResponse pickable() {
        var rows = service.list().items().stream()
                .filter(OnboardingTemplateDtos.TemplateRow::active)
                .map(r -> new OnboardingTemplateDtos.PickableTemplate(
                        r.key(),
                        r.title(),
                        r.category(),
                        r.sensitivity(),
                        r.description(),
                        r.documentType(),
                        r.currentDocumentId() != null))
                .toList();
        return new OnboardingTemplateDtos.PickableTemplateListResponse(rows);
    }
}
