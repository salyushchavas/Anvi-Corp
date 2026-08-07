package com.anvicorp.api.admin.editabletemplates;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Editable Documents Phase 1 — the admin studio's HTTP surface.
 *
 * <p>RBAC mirrors {@code OnboardingTemplateAdminController} exactly:
 * {@code hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')} on every write path.
 * The intern-side / ERM-side surfaces for filling and signing these
 * templates land in Phase 2 and are intentionally not present here.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/editable-templates")
@RequiredArgsConstructor
public class EditableTemplateAdminController {

    private final EditableTemplateAdminService service;

    // ── List + get ───────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.TemplateListResponse list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.TemplateRow get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/by-key/{key}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.TemplateRow getByKey(@PathVariable String key) {
        return service.getByKey(key);
    }

    // ── Create / update / soft-delete ────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.TemplateRow create(
            @jakarta.validation.Valid @RequestBody EditableTemplateDtos.CreateTemplateRequest req,
            @AuthenticationPrincipal User caller) {
        return service.create(req, caller);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.TemplateRow updateMetadata(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody EditableTemplateDtos.UpdateTemplateMetadataRequest req,
            @AuthenticationPrincipal User caller) {
        return service.updateMetadata(id, req, caller);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public ResponseEntity<Void> softDelete(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        service.softDelete(id, caller);
        return ResponseEntity.noContent().build();
    }

    // ── Source DOCX upload (presign → attach) ────────────────────────

    @PostMapping("/{id}/source/presign-upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.PresignUploadResponse presignUpload(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody EditableTemplateDtos.PresignUploadRequest req,
            @AuthenticationPrincipal User caller) {
        return service.presignUpload(id, req, caller);
    }

    @PostMapping("/{id}/source/attach")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.TemplateRow attachSourceFile(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody EditableTemplateDtos.AttachSourceRequest req,
            @AuthenticationPrincipal User caller) {
        return service.attachSourceFile(id, req, caller);
    }

    // ── Save canonical HTML + field schema ───────────────────────────

    @PutMapping("/{id}/schema")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'JOBS_ADMIN')")
    public EditableTemplateDtos.TemplateRow saveSchema(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody EditableTemplateDtos.SaveSchemaRequest req,
            @AuthenticationPrincipal User caller) {
        return service.saveSchema(id, req, caller);
    }
}
