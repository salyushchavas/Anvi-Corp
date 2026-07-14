package com.anvicorp.api.trainer.projects;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.AssignProjectRequest;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.ProjectDetail;
import com.anvicorp.api.trainer.projects.TrainerProjectDtos.SlotStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Trainer Phase 2 — doc §6 Project Assignment HTTP surface. */
@RestController
@RequestMapping("/api/v1/trainer/projects")
@RequiredArgsConstructor
public class TrainerProjectAssignmentController {

    private final TrainerProjectAssignmentService service;

    /** Live 2-slot indicator for the wizard's Step 1. */
    @GetMapping("/slot-status")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public SlotStatusResponse slotStatus(
            @RequestParam UUID internLifecycleId,
            @RequestParam String monthYear,
            @AuthenticationPrincipal User caller) {
        return service.getSlotStatus(internLifecycleId, monthYear, caller);
    }

    /** Assign + publish a project. JSON body — the optional project file
     *  uploads separately to {@code POST /{id}/file} after this succeeds. */
    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ProjectDetail assignProject(
            @RequestBody AssignProjectRequest req,
            @AuthenticationPrincipal User caller) {
        return service.assignProject(req, caller);
    }

    /** Attach a PDF / DOCX / ZIP starter file to an already-assigned
     *  project. Validated server-side: MIME whitelist + 50 MB cap. */
    @PostMapping(value = "/{id}/file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ProjectDetail attachFile(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User caller) {
        return service.attachProjectFile(id, file, caller);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ProjectDetail getProject(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return service.getProject(id, caller);
    }
}
