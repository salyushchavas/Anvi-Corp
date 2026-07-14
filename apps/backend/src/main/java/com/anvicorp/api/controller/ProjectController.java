package com.anvicorp.api.controller;

import com.anvicorp.api.dto.project.CreateProjectRequest;
import com.anvicorp.api.dto.project.ProjectResponse;
import com.anvicorp.api.dto.project.ReviewProjectRequest;
import com.anvicorp.api.dto.project.SubmitProjectRequest;
import com.anvicorp.api.dto.project.UpdateProgressRequest;
import com.anvicorp.api.dto.project.UpdateProjectRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.ProjectService;
import com.anvicorp.api.service.ProjectWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Project workspace endpoints. Two lanes:
 *
 * <h2>Supervisor (TECHNICAL_EVALUATOR or SUPER_ADMIN)</h2>
 * <ul>
 *   <li>POST   /projects                          — allocate</li>
 *   <li>PUT    /projects/{id}                     — edit</li>
 *   <li>GET    /projects/intern/{candidateId}     — that intern's projects</li>
 *   <li>GET    /projects/published                — my own allocations (board)</li>
 *   <li>POST   /projects/{id}/return              — return with notes</li>
 *   <li>POST   /projects/{id}/complete            — terminal lock</li>
 * </ul>
 *
 * <h2>Intern</h2>
 * <ul>
 *   <li>GET  /projects/me                         — my projects</li>
 *   <li>POST /projects/{id}/start                 — mark IN_PROGRESS</li>
 *   <li>PUT  /projects/{id}/progress              — progress + task checks</li>
 *   <li>POST /projects/{id}/submit                — submit deliverables</li>
 * </ul>
 *
 * Service-layer scoping: a TECHNICAL_EVALUATOR can only touch projects on
 * an engagement they own; SUPER_ADMIN bypasses. Intern endpoints are gated
 * to {@code hasRole('INTERN')} and the service enforces the project belongs
 * to the caller's Candidate row.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;
    private final ProjectWorkflowService workflowService;
    private final com.anvicorp.api.service.ProjectCatalogService catalogService;

    // ── Project Catalog (Project Assignment module) ─────────────────────────
    //
    // Subpath /catalog/* — kept off the controller root so the existing
    // POST /api/v1/projects (legacy single-allocate flow used by the
    // workspace + 957-LOC evaluator page) is not redefined. The new
    // catalog flow is purely additive.

    @org.springframework.web.bind.annotation.PostMapping("/catalog")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public com.anvicorp.api.dto.project.catalog.CatalogProjectResponse createCatalog(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.anvicorp.api.dto.project.catalog.CreateCatalogProjectRequest req,
            @AuthenticationPrincipal User caller) {
        return catalogService.createCatalogProject(req, caller);
    }

    @GetMapping("/catalog/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN', 'INTERN')")
    public com.anvicorp.api.dto.project.catalog.CatalogProjectResponse getCatalog(
            @PathVariable UUID id) {
        return catalogService.getCatalogProject(id);
    }

    @GetMapping("/catalog")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public java.util.List<com.anvicorp.api.dto.project.catalog.CatalogProjectResponse> listCatalog(
            @org.springframework.web.bind.annotation.RequestParam(name = "createdByMe", defaultValue = "false")
            boolean createdByMe,
            @AuthenticationPrincipal User caller) {
        if (createdByMe) return catalogService.listCreatedBy(caller.getId());
        return catalogService.listAllCatalog();
    }

    @GetMapping("/catalog/all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public java.util.List<com.anvicorp.api.dto.project.catalog.CatalogProjectResponse> listAllCatalog() {
        return catalogService.listAllCatalog();
    }

    /**
     * Trainer marks the KT (Knowledge Transfer) session done for an
     * assigned monthly project. Optional meeting link + notes. Service
     * verifies the project is assigned and the caller owns it.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/kt-done")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public com.anvicorp.api.dto.project.catalog.CatalogProjectResponse markKtDone(
            @PathVariable UUID id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody(required = false)
            com.anvicorp.api.dto.project.catalog.KtMarkRequest req,
            @AuthenticationPrincipal User caller) {
        return catalogService.markKtDone(id, req, caller);
    }

    /**
     * Trainer schedules a live KT (Knowledge Transfer) Zoom session
     * for an assigned project. Backend creates the Zoom meeting and
     * stores start_url + join_url on the project; intern is notified
     * via internal mail + in-app; trainer gets the host link email.
     * Mark-KT-done remains a separate, always-available action — no
     * gate that requires a scheduled session first.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/kt-schedule")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public com.anvicorp.api.dto.project.catalog.CatalogProjectResponse scheduleKt(
            @PathVariable UUID id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.anvicorp.api.dto.project.catalog.KtScheduleRequest req,
            @AuthenticationPrincipal User caller) {
        return catalogService.scheduleKt(id, req, caller);
    }

    @org.springframework.web.bind.annotation.PostMapping("/catalog/{id}/repository")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public com.anvicorp.api.dto.project.catalog.CatalogProjectResponse linkRepository(
            @PathVariable UUID id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.anvicorp.api.dto.project.catalog.LinkRepositoryRequest req,
            @AuthenticationPrincipal User caller) {
        return catalogService.linkRepository(id, req.repositoryName(), req.repositoryUrl(), caller);
    }

    @org.springframework.web.bind.annotation.PutMapping("/catalog/{id}/repository")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public com.anvicorp.api.dto.project.catalog.CatalogProjectResponse updateRepository(
            @PathVariable UUID id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.anvicorp.api.dto.project.catalog.LinkRepositoryRequest req,
            @AuthenticationPrincipal User caller) {
        return catalogService.updateRepository(id, req.repositoryName(), req.repositoryUrl(), caller);
    }

    // ── Supervisor commands ─────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest req,
            @AuthenticationPrincipal User user) {
        ProjectResponse created = service.create(req, user);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.getId()))
                .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ProjectResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProjectRequest req,
            @AuthenticationPrincipal User user) {
        return service.update(id, req, user);
    }

    @GetMapping("/intern/{candidateId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<ProjectResponse> listForIntern(
            @PathVariable UUID candidateId,
            @AuthenticationPrincipal User user) {
        return service.listForIntern(candidateId, user);
    }

    /** Supervisor's full board — all projects they've allocated. */
    @GetMapping("/published")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<ProjectResponse> listMine(@AuthenticationPrincipal User user) {
        return service.listMine(user);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ProjectResponse returnForChanges(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewProjectRequest req,
            @AuthenticationPrincipal User user) {
        return service.returnForChanges(id, req, user);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ProjectResponse complete(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewProjectRequest req,
            @AuthenticationPrincipal User user) {
        return service.complete(id, req, user);
    }

    // ── Intern commands (legacy single-allocation, @Deprecated) ─────────────
    //
    // These four endpoints are the pre-ProjectAssignment intern API. Phase 0
    // freezes them — the new ProjectAssignmentController.{start,submit} are
    // the canonical replacements; {progress} has no replacement (status now
    // flows implicitly). Endpoints remain on disk so any in-flight client
    // doesn't 404 immediately, but the API is closed: Phase 1+ removes them.

    @Deprecated
    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public List<ProjectResponse> listForMe(@AuthenticationPrincipal User user) {
        return service.listForMe(user);
    }

    @Deprecated
    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('INTERN')")
    public ProjectResponse start(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return service.start(id, user);
    }

    @Deprecated
    @PutMapping("/{id}/progress")
    @PreAuthorize("hasRole('INTERN')")
    public ProjectResponse updateProgress(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProgressRequest req,
            @AuthenticationPrincipal User user) {
        return service.updateProgress(id, req, user);
    }

    @Deprecated
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('INTERN')")
    public ProjectResponse submit(
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitProjectRequest req,
            @AuthenticationPrincipal User user) {
        return service.submit(id, req, user);
    }

    // ── Two-role workflow (P1b) ─────────────────────────────────────────────
    //
    // The four endpoints below sit alongside the legacy /return + /complete
    // (which are the single-reviewer path). The two-role flow is opt-in per
    // project — the workspace task will eventually drive it from the GitHub
    // PR webhook, but each endpoint is also callable directly by the
    // appropriate reviewer for stacks that don't go through GitHub.
    //
    // All thin pass-throughs to ProjectWorkflowService.

    @PostMapping("/{id}/tech-approve")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> techApprove(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        var saved = workflowService.techApprove(id, user);
        return Map.of("id", saved.getId(), "status", saved.getStatus().name());
    }

    @PostMapping("/{id}/return-revisions")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> returnForRevisions(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User user) {
        var saved = workflowService.returnForRevisions(id, user,
                body != null ? body.get("reason") : null);
        return Map.of("id", saved.getId(), "status", saved.getStatus().name());
    }

    @PostMapping("/{id}/mark-pending-viva")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> markPendingViva(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal User user) {
        Instant scheduledAt = null;
        if (body != null && body.get("scheduledAt") != null) {
            try {
                scheduledAt = Instant.parse(body.get("scheduledAt"));
            } catch (Exception ignored) {
                // tolerate malformed input — the RM can re-edit.
            }
        }
        var saved = workflowService.markPendingViva(id, user, scheduledAt);
        return Map.of("id", saved.getId(), "status", saved.getStatus().name());
    }

    @PostMapping("/{id}/complete-after-viva")
    @PreAuthorize("hasAnyRole('REPORTING_MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> completeAfterViva(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        var saved = workflowService.completeAfterViva(id, user);
        return Map.of("id", saved.getId(), "status", saved.getStatus().name());
    }
}
