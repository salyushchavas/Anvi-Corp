package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.project.CreateProjectRequest;
import com.anvicorp.api.dto.project.ProjectResponse;
import com.anvicorp.api.dto.project.ProjectSubmissionResponse;
import com.anvicorp.api.dto.project.ProjectTaskResponse;
import com.anvicorp.api.dto.project.ReviewProjectRequest;
import com.anvicorp.api.dto.project.SubmitProjectRequest;
import com.anvicorp.api.dto.project.UpdateProgressRequest;
import com.anvicorp.api.dto.project.UpdateProjectRequest;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.ProjectAssignment;
import com.anvicorp.api.entity.ProjectRepositoryLink;
import com.anvicorp.api.entity.ProjectSubmission;
import com.anvicorp.api.entity.ProjectTask;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.ProjectAssignmentStatus;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.ProjectRepositoryLinkRepository;
import com.anvicorp.api.repository.ProjectSubmissionRepository;
import com.anvicorp.api.repository.ProjectTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Project workspace service. Three lanes:
 *
 * <h3>Supervisor</h3>
 * <ul>
 *   <li>{@link #create} — assign to one of THEIR interns (engagement.supervisor
 *       check; SUPER_ADMIN bypasses)</li>
 *   <li>{@link #update} — edit fields + replace task list; blocked when COMPLETED</li>
 *   <li>{@link #returnForChanges} — RETURN with required notes (audit row)</li>
 *   <li>{@link #complete} — terminal lock (audit row)</li>
 * </ul>
 *
 * <h3>Intern</h3>
 * <ul>
 *   <li>{@link #start} — NOT_STARTED → IN_PROGRESS, stamps started_at</li>
 *   <li>{@link #updateProgress} — progress_pct + task done flips; no status change</li>
 *   <li>{@link #submit} — SUBMITTED + new ProjectSubmission row + audit</li>
 *   <li>Intern CANNOT call {@link #complete} or {@link #returnForChanges}.</li>
 * </ul>
 *
 * <h3>Audit actions</h3>
 * PROJECT_ASSIGNED / PROJECT_STARTED / PROJECT_SUBMITTED / PROJECT_RETURNED /
 * PROJECT_COMPLETED. Best-effort writes (a failure logs WARN and never blocks
 * the mutation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {};

    private final ProjectRepository projectRepository;
    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectSubmissionRepository projectSubmissionRepository;
    private final CandidateRepository candidateRepository;
    private final EngagementRepository engagementRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final com.anvicorp.api.notification.NotificationService notificationService;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ── Supervisor: allocate ────────────────────────────────────────────────

    @Transactional
    public ProjectResponse create(CreateProjectRequest req, User actor) {
        Candidate intern = candidateRepository.findById(req.getCandidateId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + req.getCandidateId()));

        // Resolve the intern's engagement; supervisor must own it (or
        // SUPER_ADMIN). We pick the newest in-funnel (non-terminated)
        // engagement — same rule the weekly cycle uses.
        Engagement engagement = engagementRepository.findByCandidateId(intern.getId()).stream()
                .filter(e -> e.getStatus() != EngagementStatus.TERMINATED)
                .max(Comparator.comparing(Engagement::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new BadRequestException(
                        "Intern has no active engagement — can't allocate a project."));

        ensureSupervisorOwnsEngagement(engagement, actor);

        Project project = Project.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .deliverables(req.getDeliverables())
                .resourceLinksJson(serializeList(req.getResourceLinks()))
                .engagement(engagement)
                .intern(intern)
                .assignedBy(actor)
                .startDate(req.getStartDate())
                .dueDate(req.getDueDate())
                .status(ProjectStatus.NOT_STARTED)
                .progressPct(0)
                .build();
        project = projectRepository.save(project);
        replaceTasks(project, req.getTaskTitles());

        // Forward-migration mirror: write a project_assignments row for the
        // new-module read path so the intern UI (which now consumes
        // /api/v1/project-assignments/mine exclusively) sees this allocation
        // immediately, without waiting for the boot-time backfill in
        // SchemaFixupRunner. Failure is non-fatal — the legacy write is the
        // primary effect, and the backfill is the safety net.
        mirrorToProjectAssignments(project, intern, actor);

        // Second mirror: if any of the TE's resource links is a GitHub repo
        // URL, also upsert a project_repositories row so the intern detail
        // page's Repository card populates without manual link-up via the
        // catalog API. Same non-fatal posture as the assignment mirror.
        mirrorRepositoryFromResourceLinks(project, actor);

        writeAudit(project.getId(), "PROJECT_ASSIGNED", actor.getId(), Map.of(
                "title", project.getTitle(),
                "candidateId", intern.getId(),
                "engagementId", engagement.getId()));

        // Batch-3 — intern gets a "new project assigned" email. Best-effort.
        Project saved = reload(project.getId());
        try {
            notificationService.sendProjectAssigned(saved);
        } catch (Exception e) {
            log.warn("PROJECT_ASSIGNED notify failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        // Phase: Employee internal-mail — sendProjectAssigned above is a
        // TYPED EmailProvider method that BridgingEmailProvider does NOT
        // intercept (only sendRendered/sendBrandedHtml are bridged). So
        // the intern's company mailbox wouldn't see this event via the
        // legacy supervisor path. notifyIntern routes through the bridge
        // explicitly and is gated on activeStatus='ACTIVE' + mailbox
        // ACTIVATED — safe to call always; skips silently for pre-active
        // interns and for trainer-side assignments that ALSO trigger
        // ProjectNotificationDispatcher (which renders + bridges its own
        // intern email, so the trainer path stays single-sent).
        try {
            UUID internUserId = intern.getUser() != null ? intern.getUser().getId() : null;
            if (internUserId != null) {
                String projectTitle = saved.getTitle() != null ? saved.getTitle() : "your project";
                String due = saved.getDueDate() != null ? " due " + saved.getDueDate() : "";
                // Actor + role per Model A: "<Name>, your <Role>, ...". On the
                // legacy supervisor path the assigner can be any of TRAINER /
                // MANAGER / REPORTING_MANAGER / SUPER_ADMIN; resolve a friendly
                // role label from their first role, defaulting to "Supervisor".
                String actorPhrase = "Your team";
                String roleWord = "Supervisor";
                if (actor != null) {
                    if (actor.getRoles() != null && !actor.getRoles().isEmpty()) {
                        UserRole firstRole = actor.getRoles().iterator().next();
                        roleWord = switch (firstRole) {
                            case TRAINER -> "Trainer";
                            case MANAGER -> "Manager";
                            case REPORTING_MANAGER -> "Reporting Manager";
                            case ERM -> "ERM";
                            case SUPER_ADMIN -> "Admin";
                            default -> "Supervisor";
                        };
                    }
                    if (actor.getFullName() != null && !actor.getFullName().isBlank()) {
                        actorPhrase = actor.getFullName() + ", your " + roleWord + ",";
                    } else {
                        actorPhrase = "Your " + roleWord;
                    }
                }
                String body = actorPhrase + " has assigned you a new project: \""
                        + projectTitle + "\"" + due + "."
                        + "\n\nOpen your projects: /careers/intern/projects"
                        + "\n\n" + brand.signoff();
                internNotifications.notifyIntern(internUserId,
                        "New project assigned by your " + roleWord + ": " + projectTitle,
                        body, null);
            }
        } catch (Exception e) {
            log.warn("PROJECT_ASSIGNED internal-mail notify failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        return toResponse(saved);
    }

    // ── Supervisor: edit ────────────────────────────────────────────────────

    @Transactional
    public ProjectResponse update(UUID projectId, UpdateProjectRequest req, User actor) {
        Project project = projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        ensureSupervisorOwnsProject(project, actor);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new ConflictException(
                    "This project is COMPLETED and locked. Allocate a new one to extend.");
        }

        if (req.getTitle() != null) project.setTitle(req.getTitle());
        if (req.getDescription() != null) project.setDescription(req.getDescription());
        if (req.getDeliverables() != null) project.setDeliverables(req.getDeliverables());
        if (req.getResourceLinks() != null) {
            project.setResourceLinksJson(serializeList(req.getResourceLinks()));
        }
        if (req.getStartDate() != null) project.setStartDate(req.getStartDate());
        if (req.getDueDate() != null) project.setDueDate(req.getDueDate());

        project = projectRepository.save(project);
        if (req.getTaskTitles() != null) {
            replaceTasks(project, req.getTaskTitles());
        }
        return toResponse(reload(project.getId()));
    }

    // ── Supervisor: read intern roster ──────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForIntern(UUID candidateId, User actor) {
        Candidate intern = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));
        ensureSupervisorCanReview(intern, actor);
        return projectRepository.findByInternIdWithGraph(intern.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Supervisor's full allocation board (their own assignments). */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listMine(User actor) {
        return projectRepository.findByAssignedByIdWithGraph(actor.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Supervisor: review ──────────────────────────────────────────────────

    @Transactional
    public ProjectResponse returnForChanges(UUID projectId, ReviewProjectRequest req, User actor) {
        Project project = requireForReview(projectId, actor);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            return toResponse(project); // idempotent — already locked
        }
        if (project.getStatus() == ProjectStatus.NOT_STARTED
                || project.getStatus() == ProjectStatus.IN_PROGRESS) {
            throw new BadRequestException(
                    "Can't return a project that hasn't been submitted yet.");
        }
        if (req == null || req.getReviewNotes() == null || req.getReviewNotes().isBlank()) {
            throw new BadRequestException(
                    "Review notes are required when returning a project for changes.");
        }
        project.setStatus(ProjectStatus.RETURNED);
        project.setReviewedBy(actor);
        project.setReviewedAt(Instant.now());
        project.setReviewNotes(req.getReviewNotes().trim());
        project = projectRepository.save(project);

        writeAudit(project.getId(), "PROJECT_RETURNED", actor.getId(), Map.of(
                "candidateId", project.getIntern().getId()));

        // Batch-3 — intern gets a "project returned" email with review notes.
        // Best-effort.
        Project saved = reload(project.getId());
        try {
            notificationService.sendProjectReturned(saved);
        } catch (Exception e) {
            log.warn("PROJECT_RETURNED notify failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        // Tier A — typed notificationService.sendProjectReturned uses
        // sendProjectReturned which bypasses BridgingEmailProvider, so the
        // intern's company mailbox wouldn't see it. notifyIntern routes
        // through the bridge for ACTIVE interns.
        try {
            UUID internUserId = saved.getIntern() != null
                    && saved.getIntern().getUser() != null
                    ? saved.getIntern().getUser().getId() : null;
            if (internUserId != null) {
                String title = saved.getTitle() != null ? saved.getTitle() : "your project";
                String reviewNotes = saved.getReviewNotes();
                String actorPhrase = actor != null && actor.getFullName() != null
                        && !actor.getFullName().isBlank()
                        ? actor.getFullName() + ", your Trainer,"
                        : "Your Trainer";
                String subject = "Project '" + title + "' returned by your Trainer";
                String body = "Hi,\n\n" + actorPhrase + " returned \"" + title
                        + "\" for changes."
                        + (reviewNotes != null && !reviewNotes.isBlank()
                            ? "\n\nReview notes: " + reviewNotes : "")
                        + "\n\nOpen the project: /careers/intern/projects/" + saved.getId()
                        + "\n\n" + brand.signoff();
                internNotifications.notifyIntern(internUserId, subject, body, null);
            }
        } catch (Exception e) {
            log.warn("PROJECT_RETURNED internal-mail failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        return toResponse(saved);
    }

    @Transactional
    public ProjectResponse complete(UUID projectId, ReviewProjectRequest req, User actor) {
        Project project = requireForReview(projectId, actor);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            return toResponse(project); // idempotent re-click
        }
        if (project.getStatus() == ProjectStatus.NOT_STARTED
                || project.getStatus() == ProjectStatus.IN_PROGRESS) {
            throw new BadRequestException(
                    "Can't complete a project that hasn't been submitted yet.");
        }
        Instant now = Instant.now();
        project.setStatus(ProjectStatus.COMPLETED);
        project.setReviewedBy(actor);
        project.setReviewedAt(now);
        project.setCompletedAt(now);
        project.setProgressPct(100);
        if (req != null && req.getReviewNotes() != null && !req.getReviewNotes().isBlank()) {
            project.setReviewNotes(req.getReviewNotes().trim());
        }
        project = projectRepository.save(project);

        writeAudit(project.getId(), "PROJECT_COMPLETED", actor.getId(), Map.of(
                "candidateId", project.getIntern().getId()));

        // Batch-3 — intern gets a "project completed" email. Best-effort.
        Project saved = reload(project.getId());
        try {
            notificationService.sendProjectCompleted(saved);
        } catch (Exception e) {
            log.warn("PROJECT_COMPLETED notify failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        // Tier A — intern internal-mail is handled by
        // EmailNotifierListener.onCompleted on the ProjectCompletedEvent
        // we publish below. That listener resolves the actor's role
        // dynamically (Trainer here on the legacy path, Reporting Manager
        // on the two-role viva path) so both flows produce correct
        // Model-A wording without duplicating the send.

        // Two-role workflow prerequisite — emit ProjectCompletedEvent from
        // BOTH paths (this legacy single-reviewer complete + the
        // ProjectWorkflowService.completeAfterViva path) so downstream
        // listeners (offboarding, badges, portfolio link) don't need to
        // know which reviewer signed off.
        try {
            eventPublisher.publishEvent(
                    new com.anvicorp.api.event.project.ProjectCompletedEvent(
                            saved.getId(), actor != null ? actor.getId() : null));
        } catch (Exception e) {
            log.warn("ProjectCompletedEvent publish failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        return toResponse(saved);
    }

    // ── Intern: read + lifecycle ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForMe(User candidateUser) {
        Candidate candidate = candidateRepository.findByUserId(candidateUser.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Projects are visible to interns only."));
        return projectRepository.findByInternIdWithGraph(candidate.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse start(UUID projectId, User candidateUser) {
        Project project = requireInternOwned(projectId, candidateUser);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new ConflictException(
                    "This project is completed and locked.");
        }
        if (project.getStatus() == ProjectStatus.NOT_STARTED) {
            project.setStatus(ProjectStatus.IN_PROGRESS);
            project.setStartedAt(Instant.now());
            project = projectRepository.save(project);
            writeAudit(project.getId(), "PROJECT_STARTED", candidateUser.getId(), Map.of(
                    "candidateId", project.getIntern().getId()));
        }
        return toResponse(reload(project.getId()));
    }

    @Transactional
    public ProjectResponse updateProgress(UUID projectId, UpdateProgressRequest req,
                                          User candidateUser) {
        Project project = requireInternOwned(projectId, candidateUser);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new ConflictException(
                    "This project is completed and locked.");
        }
        // Auto-flip NOT_STARTED → IN_PROGRESS on first progress update.
        if (project.getStatus() == ProjectStatus.NOT_STARTED) {
            project.setStatus(ProjectStatus.IN_PROGRESS);
            project.setStartedAt(Instant.now());
        }
        if (req.getProgressPct() != null) {
            int clamped = Math.max(0, Math.min(100, req.getProgressPct()));
            project.setProgressPct(clamped);
        }
        if (req.getTaskUpdates() != null) {
            Map<UUID, ProjectTask> byId = new HashMap<>();
            for (ProjectTask t : projectTaskRepository
                    .findByProjectIdOrderBySortOrderAsc(project.getId())) {
                byId.put(t.getId(), t);
            }
            for (UpdateProgressRequest.TaskUpdate u : req.getTaskUpdates()) {
                if (u.getTaskId() == null || u.getDone() == null) continue;
                ProjectTask task = byId.get(u.getTaskId());
                if (task == null) continue; // wrong project — silently ignored
                task.setDone(u.getDone());
                projectTaskRepository.save(task);
            }
        }
        project = projectRepository.save(project);
        return toResponse(reload(project.getId()));
    }

    @Transactional
    public ProjectResponse submit(UUID projectId, SubmitProjectRequest req, User candidateUser) {
        Project project = requireInternOwned(projectId, candidateUser);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new ConflictException(
                    "This project is completed and locked.");
        }
        if (project.getStatus() == ProjectStatus.NOT_STARTED) {
            project.setStatus(ProjectStatus.IN_PROGRESS);
            project.setStartedAt(Instant.now());
        }
        Instant now = Instant.now();
        project.setStatus(ProjectStatus.SUBMITTED);
        project.setSubmittedAt(now);
        project = projectRepository.save(project);

        ProjectSubmission sub = ProjectSubmission.builder()
                .project(project)
                .description(req != null ? req.getDescription() : null)
                .linksJson(req != null ? serializeList(req.getLinks()) : null)
                .submittedAt(now)
                .build();
        projectSubmissionRepository.save(sub);

        writeAudit(project.getId(), "PROJECT_SUBMITTED", candidateUser.getId(), Map.of(
                "candidateId", project.getIntern().getId()));

        // Batch-3 — supervisor gets a "project submitted by {intern}" email.
        // Best-effort.
        Project saved = reload(project.getId());
        try {
            notificationService.sendProjectSubmitted(saved);
        } catch (Exception e) {
            log.warn("PROJECT_SUBMITTED notify failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        return toResponse(saved);
    }

    // ── Gate helpers ────────────────────────────────────────────────────────

    private Project requireForReview(UUID projectId, User actor) {
        Project project = projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        ensureSupervisorOwnsProject(project, actor);
        return project;
    }

    private Project requireInternOwned(UUID projectId, User candidateUser) {
        Project project = projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        Candidate candidate = candidateRepository.findByUserId(candidateUser.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Projects are visible to interns only."));
        if (project.getIntern() == null
                || !project.getIntern().getId().equals(candidate.getId())) {
            // Don't leak existence.
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
        // Active-engagement gate — mirrors weekly cycle.
        List<Engagement> active = engagementRepository
                .findByCandidateIdAndStatus(candidate.getId(), EngagementStatus.ACTIVE);
        if (active.isEmpty()) {
            throw new ForbiddenException(
                    "Projects can only be worked while your engagement is active.");
        }
        return project;
    }

    // Role-based gates — any TECHNICAL_EVALUATOR (or SUPER_ADMIN) may
    // allocate / review / mutate any project. Per-engagement supervisor FK
    // is informational metadata, not a permission boundary.
    private void ensureSupervisorOwnsEngagement(Engagement engagement, User actor) {
        ensureTechnicalSupervisorRole(actor);
    }

    private void ensureSupervisorOwnsProject(Project project, User actor) {
        ensureTechnicalSupervisorRole(actor);
    }

    private void ensureSupervisorCanReview(Candidate candidate, User actor) {
        ensureTechnicalSupervisorRole(actor);
    }

    private static void ensureTechnicalSupervisorRole(User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(actor)) return;
        if (actor.getRoles() != null
                && actor.getRoles().contains(UserRole.TRAINER)) {
            return;
        }
        throw new ForbiddenException(
                "Only TECHNICAL_EVALUATOR or SUPER_ADMIN may perform this action.");
    }

    private static boolean isSuperAdmin(User u) {
        return u.getRoles() != null && u.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Project reload(UUID id) {
        return projectRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
    }

    private void replaceTasks(Project project, List<String> taskTitles) {
        // Wipe + recreate. Project task ids change on edit — acceptable because
        // tasks have no foreign references and are intentionally lightweight.
        projectTaskRepository.deleteByProjectId(project.getId());
        if (taskTitles == null || taskTitles.isEmpty()) return;
        int order = 0;
        for (String title : taskTitles) {
            if (title == null || title.isBlank()) continue;
            ProjectTask task = ProjectTask.builder()
                    .project(project)
                    .title(title.trim())
                    .done(false)
                    .sortOrder(order++)
                    .build();
            projectTaskRepository.save(task);
        }
    }

    /**
     * Forward-migration helper. The intern UI now reads exclusively from
     * {@code /api/v1/project-assignments/mine}, but the TE allocation surface
     * still writes legacy {@code projects} rows via {@link #create}. This
     * method mirrors the legacy write into a {@code project_assignments} row
     * so the intern sees the allocation on the very next 30s poll — without
     * waiting for the boot-time backfill in {@code SchemaFixupRunner}.
     *
     * <p>Important: the new module keys assignments on {@code users.id}, while
     * the legacy {@code Project.intern} FK points at {@code candidates.id}.
     * We resolve the candidate's owning user here.</p>
     *
     * <p>Idempotency: silently skip if an active assignment row already exists
     * for this (project_id, intern_user_id) — the boot backfill may have
     * created one before this code ran. Re-mirroring on every TE click
     * would otherwise pile up duplicate rows.</p>
     *
     * <p>Failure posture: every exception is caught and logged. The legacy
     * write has already committed; the mirror is a downstream signal, and
     * the reconciliation backfill is the safety net.</p>
     */
    private void mirrorToProjectAssignments(Project project, Candidate intern, User actor) {
        try {
            if (project == null || intern == null || actor == null) return;
            User internUser = intern.getUser();
            if (internUser == null) {
                log.warn("Mirror to project_assignments skipped — candidate {} has no user row",
                        intern.getId());
                return;
            }
            UUID internUserId = internUser.getId();
            UUID projectId = project.getId();

            // Skip if an active assignment row already exists for this pair.
            // Re-assignments deliberately keep history, but the legacy /create
            // is one-to-one with a Project row, so duplicating from a mirror
            // would be noise.
            boolean exists = projectAssignmentRepository
                    .findByProjectIdOrderByAssignmentDateDescCreatedAtDesc(projectId)
                    .stream()
                    .anyMatch(a -> internUserId.equals(a.getInternId()));
            if (exists) {
                log.debug("Mirror to project_assignments skipped — row already exists for "
                        + "project={} intern={}", projectId, internUserId);
                return;
            }

            java.time.LocalDate assignmentDate = project.getStartDate() != null
                    ? project.getStartDate()
                    : java.time.LocalDate.now();

            ProjectAssignment mirror = ProjectAssignment.builder()
                    .projectId(projectId)
                    .internId(internUserId)
                    .assignedById(actor.getId())
                    .assignmentDate(assignmentDate)
                    .dueDate(project.getDueDate())
                    .remarks(null)
                    .status(ProjectAssignmentStatus.ASSIGNED)
                    .accessGranted(Boolean.FALSE)
                    .build();
            ProjectAssignment saved = projectAssignmentRepository.save(mirror);

            // Audit row makes the bridge visible. Same Project audit table —
            // entityType=PROJECT_ASSIGNMENT distinguishes from the legacy
            // PROJECT_ASSIGNED row written by writeAudit() below.
            try {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("legacyProjectId", projectId.toString());
                meta.put("internUserId", internUserId.toString());
                meta.put("trigger", "LEGACY_CREATE_MIRROR");
                AuditLog audit = AuditLog.builder()
                        .entityType("PROJECT_ASSIGNMENT")
                        .entityId(saved.getId())
                        .action("PROJECT_ASSIGNMENT_MIRRORED_FROM_LEGACY")
                        .userId(actor.getId())
                        .afterJson(serializeJson(meta))
                        .build();
                auditLogRepository.save(audit);
            } catch (Exception auditErr) {
                log.warn("Mirror audit-row write failed (non-fatal) for assignment {}: {}",
                        saved.getId(), auditErr.getMessage());
            }
            log.info("Mirrored legacy project {} into project_assignments row {} for intern {}",
                    projectId, saved.getId(), internUserId);
        } catch (Exception e) {
            log.error("mirrorToProjectAssignments failed (non-fatal — legacy create committed) "
                    + "for project {}: {}",
                    project != null ? project.getId() : "<null>", e.getMessage(), e);
        }
    }

    /**
     * Recognises a GitHub repo browse URL — {@code https://github.com/{owner}/{name}}
     * with optional {@code .git} suffix and trailing slash. Gists, GitLab,
     * Bitbucket, and any non-conforming string return no match. The {@code name}
     * capture is the bare repo name with the {@code .git} suffix stripped.
     */
    private static final java.util.regex.Pattern GITHUB_REPO_URL = java.util.regex.Pattern
            .compile("^https?://github\\.com/([\\w.-]+)/([\\w.-]+?)(\\.git)?/?$");

    /**
     * Parsed GitHub URL — kept locally so the helper has a clean return type
     * without leaking regex bookkeeping to callers.
     */
    record ParsedGitHubUrl(String owner, String name, String url) {}

    /**
     * Picks the first GitHub repo URL out of an arbitrary URL list. Non-GitHub
     * entries (Gist, GitLab, blog posts, docs) are skipped. Returns null when
     * no entry matches the GitHub repo pattern.
     */
    static ParsedGitHubUrl extractGithubRepoUrl(List<String> urls) {
        if (urls == null) return null;
        for (String raw : urls) {
            if (raw == null) continue;
            String url = raw.trim();
            if (url.isEmpty()) continue;
            java.util.regex.Matcher m = GITHUB_REPO_URL.matcher(url);
            if (m.matches()) {
                String owner = m.group(1);
                String name = m.group(2); // no .git — group(3) is the optional suffix
                return new ParsedGitHubUrl(owner, name, url);
            }
        }
        return null;
    }

    /**
     * Forward-migration mirror, part 2 — if any of the legacy resource links
     * is a GitHub repo URL, upsert a {@link ProjectRepositoryLink} row so the
     * intern detail page renders the Repository card without manual link-up.
     *
     * <p>Behaviour:</p>
     * <ul>
     *   <li>No resource links / no GitHub URL → no-op.</li>
     *   <li>Existing {@code project_repositories} row for this project →
     *       update {@code repository_name} + {@code repository_url} +
     *       {@code linked_by_id}; {@code @UpdateTimestamp} bumps
     *       {@code updated_at} on save. TEs re-allocating with a new URL
     *       are reflected immediately.</li>
     *   <li>No existing row → insert with {@code linked_by_id} = the TE
     *       performing the allocation.</li>
     * </ul>
     *
     * <p>Failure posture matches the assignment mirror: every exception is
     * caught and logged; the legacy create + assignment mirror have already
     * committed, and the {@code SchemaFixupRunner} backfill is the safety
     * net for retroactive repair.</p>
     */
    private void mirrorRepositoryFromResourceLinks(Project project, User actor) {
        try {
            if (project == null || project.getResourceLinksJson() == null) return;
            List<String> urls = deserializeList(project.getResourceLinksJson());
            ParsedGitHubUrl parsed = extractGithubRepoUrl(urls);
            if (parsed == null) {
                log.debug("No GitHub URL in resource links for project {} — repository mirror skipped",
                        project.getId());
                return;
            }

            UUID projectId = project.getId();
            ProjectRepositoryLink existing = projectRepositoryLinkRepository
                    .findByProjectId(projectId).orElse(null);

            ProjectRepositoryLink saved;
            boolean inserted;
            if (existing != null) {
                existing.setRepositoryName(parsed.name());
                existing.setRepositoryUrl(parsed.url());
                existing.setLinkedById(actor != null ? actor.getId() : existing.getLinkedById());
                saved = projectRepositoryLinkRepository.save(existing);
                inserted = false;
            } else {
                ProjectRepositoryLink fresh = ProjectRepositoryLink.builder()
                        .projectId(projectId)
                        .repositoryName(parsed.name())
                        .repositoryUrl(parsed.url())
                        .linkedById(actor != null ? actor.getId() : null)
                        .build();
                saved = projectRepositoryLinkRepository.save(fresh);
                inserted = true;
            }

            // Note the runners-up so a TE who entered multiple GitHub URLs can
            // see in the logs that only the first was auto-linked.
            if (urls != null) {
                int countGh = 0;
                for (String u : urls) {
                    if (u != null && GITHUB_REPO_URL.matcher(u.trim()).matches()) countGh++;
                }
                if (countGh > 1) {
                    log.info("Project {} resource links carried {} GitHub URLs — only the first ({}) was auto-linked.",
                            projectId, countGh, parsed.url());
                }
            }

            try {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("projectId", projectId.toString());
                meta.put("url", parsed.url());
                meta.put("trigger", "LEGACY_CREATE_MIRROR");
                meta.put("op", inserted ? "INSERT" : "UPDATE");
                AuditLog audit = AuditLog.builder()
                        .entityType("PROJECT_REPOSITORY")
                        .entityId(saved.getId())
                        .action("PROJECT_REPOSITORY_MIRRORED_FROM_LEGACY")
                        .userId(actor != null ? actor.getId() : null)
                        .afterJson(serializeJson(meta))
                        .build();
                auditLogRepository.save(audit);
            } catch (Exception auditErr) {
                log.warn("Repository-mirror audit write failed (non-fatal) for project {}: {}",
                        projectId, auditErr.getMessage());
            }

            log.info("Mirrored GitHub URL from resource links into project_repositories for project {} ({} → {})",
                    projectId, inserted ? "INSERT" : "UPDATE", parsed.url());
        } catch (Exception e) {
            log.warn("mirrorRepositoryFromResourceLinks failed (non-fatal — legacy create committed) "
                    + "for project {}: {}",
                    project != null ? project.getId() : "<null>", e.getMessage(), e);
        }
    }

    private void writeAudit(UUID projectId, String action, UUID userId,
                            Map<String, Object> snapshot) {
        Map<String, Object> after = snapshot != null
                ? new LinkedHashMap<>(snapshot) : new LinkedHashMap<>();
        AuditLog entry = AuditLog.builder()
                .entityType("Project")
                .entityId(projectId)
                .action(action)
                .userId(userId)
                .afterJson(serializeJson(after))
                .build();
        try {
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write {} audit row (non-fatal): {}", action, e.getMessage());
        }
    }

    private String serializeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize project audit snapshot: {}", e.getMessage());
            return String.valueOf(snapshot);
        }
    }

    private String serializeList(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize string list: {}", e.getMessage());
            return values.toString();
        }
    }

    private List<String> deserializeList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse stored list JSON: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private ProjectResponse toResponse(Project p) {
        List<ProjectTask> tasks = projectTaskRepository
                .findByProjectIdOrderBySortOrderAsc(p.getId());
        List<ProjectSubmission> submissions = projectSubmissionRepository
                .findByProjectIdOrderBySubmittedAtDesc(p.getId());

        Candidate intern = p.getIntern();
        User internUser = intern != null ? intern.getUser() : null;
        User assignedBy = p.getAssignedBy();
        User reviewedBy = p.getReviewedBy();

        return ProjectResponse.builder()
                .id(p.getId())
                .title(p.getTitle())
                .description(p.getDescription())
                .deliverables(p.getDeliverables())
                .resourceLinks(deserializeList(p.getResourceLinksJson()))
                .internCandidateId(intern != null ? intern.getId() : null)
                .internName(internUser != null ? internUser.getFullName() : null)
                .engagementId(p.getEngagement() != null ? p.getEngagement().getId() : null)
                .assignedById(assignedBy != null ? assignedBy.getId() : null)
                .assignedByName(assignedBy != null ? assignedBy.getFullName() : null)
                .startDate(p.getStartDate())
                .dueDate(p.getDueDate())
                .status(p.getStatus())
                .progressPct(p.getProgressPct())
                .reviewNotes(p.getReviewNotes())
                .reviewedById(reviewedBy != null ? reviewedBy.getId() : null)
                .reviewedByName(reviewedBy != null ? reviewedBy.getFullName() : null)
                .reviewedAt(p.getReviewedAt())
                .createdAt(p.getCreatedAt())
                .startedAt(p.getStartedAt())
                .submittedAt(p.getSubmittedAt())
                .completedAt(p.getCompletedAt())
                .updatedAt(p.getUpdatedAt())
                .tasks(tasks.stream().map(this::toTaskResponse).toList())
                .submissions(submissions.stream().map(this::toSubmissionResponse).toList())
                .build();
    }

    private ProjectTaskResponse toTaskResponse(ProjectTask t) {
        return ProjectTaskResponse.builder()
                .id(t.getId())
                .title(t.getTitle())
                .done(t.getDone())
                .sortOrder(t.getSortOrder())
                .build();
    }

    private ProjectSubmissionResponse toSubmissionResponse(ProjectSubmission s) {
        return ProjectSubmissionResponse.builder()
                .id(s.getId())
                .description(s.getDescription())
                .links(deserializeList(s.getLinksJson()))
                .submittedAt(s.getSubmittedAt())
                .build();
    }
}
