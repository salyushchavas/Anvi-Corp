package com.anvicorp.api.trainer.projects;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.CreateTemplateRequest;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.TemplateDetail;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.TemplateListPage;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.TemplateRow;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.UpdateTemplateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Trainer Phase 2 — Project Templates catalog HTTP surface. */
@RestController
@RequestMapping("/api/v1/trainer/project-templates")
@RequiredArgsConstructor
public class TrainerProjectTemplateController {

    private final TrainerProjectTemplateService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateListPage list(
            @RequestParam(required = false) String technologyArea,
            @RequestParam(required = false, defaultValue = "ALL") String published,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.list(technologyArea, published, search, page, pageSize, caller);
    }

    /** Used by the Project Assignment Wizard's "Use Template" picker. */
    @GetMapping("/for-wizard")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public List<TemplateRow> forWizard(
            @RequestParam(required = false) String technologyArea,
            @AuthenticationPrincipal User caller) {
        return service.listPublishedForWizard(technologyArea, caller);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail get(@PathVariable UUID id,
                              @AuthenticationPrincipal User caller) {
        return service.get(id, caller);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail create(@RequestBody CreateTemplateRequest req,
                                  @AuthenticationPrincipal User caller) {
        return service.create(req, caller);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail update(@PathVariable UUID id,
                                  @RequestBody UpdateTemplateRequest req,
                                  @AuthenticationPrincipal User caller) {
        return service.update(id, req, caller);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail publish(@PathVariable UUID id,
                                   @AuthenticationPrincipal User caller) {
        return service.publish(id, caller);
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail unpublish(@PathVariable UUID id,
                                     @AuthenticationPrincipal User caller) {
        return service.unpublish(id, caller);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail archive(@PathVariable UUID id,
                                   @AuthenticationPrincipal User caller) {
        return service.archive(id, caller);
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail unarchive(@PathVariable UUID id,
                                     @AuthenticationPrincipal User caller) {
        return service.unarchive(id, caller);
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail duplicate(@PathVariable UUID id,
                                     @AuthenticationPrincipal User caller) {
        return service.duplicate(id, caller);
    }

    @PostMapping(value = "/{id}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail attach(@PathVariable UUID id,
                                  @RequestParam("file") MultipartFile file,
                                  @AuthenticationPrincipal User caller) {
        return service.attachFile(id, file, caller);
    }

    @DeleteMapping("/{id}/attachments/{documentId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'ERM', 'SUPER_ADMIN')")
    public TemplateDetail detach(@PathVariable UUID id,
                                  @PathVariable UUID documentId,
                                  @AuthenticationPrincipal User caller) {
        return service.detachFile(id, documentId, caller);
    }
}
