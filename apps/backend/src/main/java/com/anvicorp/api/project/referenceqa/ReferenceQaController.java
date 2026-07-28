package com.anvicorp.api.project.referenceqa;

import com.anvicorp.api.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Trainer-authored reference Q&amp;A on a Project.
 *
 * <p>Read is intentionally exposed at the neutral
 * {@code /api/v1/projects/{id}/reference-qa} path (not
 * {@code /evaluator/...}) so both the evaluator's compose screen and
 * the trainer's own review screen consume the same endpoint. Write is
 * scoped under {@code /trainer/projects/{id}/reference-qa} — matches
 * the {@code TrainerProjectAssignmentController} layout convention.</p>
 */
@RestController
@RequiredArgsConstructor
public class ReferenceQaController {

    private final ReferenceQaService service;

    /** RBAC: EVALUATOR, TRAINER, SUPER_ADMIN. */
    @GetMapping("/api/v1/projects/{projectId}/reference-qa")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'TRAINER', 'SUPER_ADMIN')")
    public ReferenceQaDtos.ReferenceQaResponse read(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal User caller) {
        return service.read(projectId, caller);
    }

    /** RBAC: TRAINER, SUPER_ADMIN. Full-list replacement. */
    @PutMapping("/api/v1/trainer/projects/{projectId}/reference-qa")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ReferenceQaDtos.ReferenceQaResponse write(
            @PathVariable UUID projectId,
            @Valid @RequestBody ReferenceQaDtos.ReferenceQaWriteRequest req,
            @AuthenticationPrincipal User caller) {
        return service.write(projectId, req, caller);
    }
}
