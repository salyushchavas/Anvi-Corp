package com.anvicorp.api.service;

import com.anvicorp.api.dto.project.catalog.AssignProjectRequest;
import com.anvicorp.api.dto.project.catalog.AssignProjectResultResponse;
import com.anvicorp.api.dto.project.catalog.EligibleInternResponse;
import com.anvicorp.api.dto.project.catalog.ProjectAssignmentResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.ProjectAssignment;
import com.anvicorp.api.entity.ProjectSubmission;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.ProjectAssignmentStatus;
import com.anvicorp.api.enums.TimesheetStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WeeklyReportStatus;
import com.anvicorp.api.event.ProjectSubmittedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.github.GitHubIntegrationException;
import com.anvicorp.api.github.GitHubService;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.ProjectSubmissionRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assignment half of the Project Catalog + Assignment module. Validates
 * eligibility (intern role + active engagement) per intern, then inserts
 * one {@link ProjectAssignment} row per successful intern. Partial
 * success is the norm — failures are returned in the response rather
 * than aborting the whole batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectAssignmentService {

    private static final List<EngagementStatus> ELIGIBLE_STATUSES = List.of(
            EngagementStatus.READY_TO_START,
            EngagementStatus.ACTIVE);

    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final EngagementRepository engagementRepository;
    private final com.anvicorp.api.repository.ProjectRepositoryLinkRepository
            repositoryLinkRepository;
    private final GitHubService gitHubService;
    private final LifecycleAccessPolicy lifecycleAccessPolicy;
    private final ProjectSubmissionRepository projectSubmissionRepository;
    private final com.anvicorp.api.repository.QaSessionRepository qaSessionRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;
    private final DocumentRepository documentRepository;
    private final DocumentVaultService documentVault;
    private final CandidateRepository candidateRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final TimesheetRepository timesheetRepository;
    private final EngagementService engagementService;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /**
     * GitHub repo URL parsing — same shape as the legacy resource-link mirror
     * in ProjectService. Used to derive {@code owner / repo} from the link
     * row before calling the collaborator API.
     */
    private static final java.util.regex.Pattern GITHUB_REPO_URL =
            java.util.regex.Pattern.compile("^https?://github\\.com/([\\w.-]+)/([\\w.-]+?)(\\.git)?/?$");

    @Transactional
    public AssignProjectResultResponse assignToInterns(
            AssignProjectRequest req, User actor) {
        Project project = projectRepository.findById(req.projectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + req.projectId()));
        // Repository must be linked before the TE can assign — interns
        // need a place to push code on day one.
        if (!repositoryLinkRepository.existsByProjectId(project.getId())) {
            throw new BadRequestException(
                    "Project must have a repository linked before assigning.");
        }
        if (req.assignmentDate() == null) {
            throw new BadRequestException("assignmentDate is required");
        }
        if (req.dueDate() != null && req.dueDate().isBefore(req.assignmentDate())) {
            throw new BadRequestException(
                    "dueDate must be on or after assignmentDate");
        }
        if (req.internIds() == null || req.internIds().isEmpty()) {
            throw new BadRequestException("internIds must contain at least one user id");
        }

        // Pre-resolve every candidate's engagement so we can decide
        // eligibility per intern without N+1 lookups.
        Map<UUID, Boolean> eligibility = computeEligibility(req.internIds());

        List<AssignProjectResultResponse.Created> created = new ArrayList<>();
        List<AssignProjectResultResponse.Failure> failures = new ArrayList<>();

        for (UUID internId : req.internIds()) {
            try {
                Boolean eligible = eligibility.get(internId);
                if (eligible == null) {
                    failures.add(new AssignProjectResultResponse.Failure(
                            internId, "User not found"));
                    continue;
                }
                if (!eligible) {
                    failures.add(new AssignProjectResultResponse.Failure(
                            internId,
                            "User is not a hired intern (no active engagement)"));
                    continue;
                }
                // Phase 8: don't assign new projects to an exited intern.
                if (!lifecycleAccessPolicy.canWrite(actor, internId,
                        LifecycleAccessPolicy.WriteIntent.CREATE_NEW)) {
                    failures.add(new AssignProjectResultResponse.Failure(
                            internId, "Internship is inactive"));
                    continue;
                }
                ProjectAssignment a = ProjectAssignment.builder()
                        .projectId(project.getId())
                        .internId(internId)
                        .assignedById(actor.getId())
                        .assignmentDate(req.assignmentDate())
                        .dueDate(req.dueDate())
                        .remarks(req.remarks())
                        .status(ProjectAssignmentStatus.ASSIGNED)
                        .accessGranted(Boolean.FALSE)
                        .build();
                a = projectAssignmentRepository.save(a);
                created.add(new AssignProjectResultResponse.Created(
                        a.getId(), internId, "CREATED"));
                log.info("[ProjectAssignmentService] assigned project={} intern={} by={} id={}",
                        project.getId(), internId, actor.getId(), a.getId());

                // Tier A — internal mail to the active intern. notifyIntern
                // gates on ACTIVE + mailbox ACTIVATED, so pre-active or
                // not-yet-handed-over interns skip silently.
                try {
                    String projectTitle = project.getName() != null
                            ? project.getName()
                            : (project.getTitle() != null ? project.getTitle() : "your new project");
                    String due = a.getDueDate() != null ? " due " + a.getDueDate() : "";
                    String actorPhrase = actor != null && actor.getFullName() != null
                            && !actor.getFullName().isBlank()
                            ? actor.getFullName() + ", your Trainer,"
                            : "Your Trainer";
                    String subject = "New project assigned by your Trainer: " + projectTitle;
                    String plain = "Hi,\n\n" + actorPhrase + " has assigned you a new project: \""
                            + projectTitle + "\"" + due + "."
                            + "\n\nOpen your projects: /careers/intern/projects"
                            + "\n\n" + brand.signoff();
                    internNotifications.notifyIntern(internId, subject, plain, null);
                } catch (Exception ex) {
                    log.warn("[ProjectAssignmentService] catalog-assign intern-mail failed (non-fatal) intern={}: {}",
                            internId, ex.getMessage());
                }
            } catch (Exception e) {
                failures.add(new AssignProjectResultResponse.Failure(
                        internId, e.getMessage()));
                log.warn("[ProjectAssignmentService] assign failed project={} intern={}: {}",
                        project.getId(), internId, e.getMessage());
            }
        }
        return new AssignProjectResultResponse(created, failures);
    }

    // ── Out-of-band access tracking + lifecycle transitions ────────────────

    @Transactional
    public ProjectAssignmentResponse markAccessGranted(UUID assignmentId, User actor) {
        ensureStaff(actor);
        ProjectAssignment a = loadAssignment(assignmentId);
        if (Boolean.TRUE.equals(a.getAccessGranted())) {
            return mapWithGraph(List.of(a)).get(0);
        }

        // When the GitHub App is configured we actually invite the user as a
        // repo collaborator. When it isn't, the flag flip remains a purely
        // platform-internal acknowledgement (the TE invited them on GitHub
        // manually) — same semantics as before. Both paths share the
        // precondition that we know which repo + which GitHub user.
        Long invitationId = null;
        if (gitHubService.isConfigured()) {
            // Resolve intern GitHub username from the user row.
            User internUser = userRepository.findById(a.getInternId())
                    .orElseThrow(() -> new BadRequestException(
                            "Intern user not found for this assignment."));
            String ghUsername = internUser.getGithubUsername();
            if (ghUsername == null || ghUsername.isBlank()) {
                throw new BadRequestException(
                        "Intern has not set a GitHub username yet. They must add it on "
                                + "their assignment page before access can be granted.");
            }

            // Resolve owner / repo from the project's linked repository.
            com.anvicorp.api.entity.ProjectRepositoryLink link = repositoryLinkRepository
                    .findByProjectId(a.getProjectId())
                    .orElseThrow(() -> new BadRequestException(
                            "No repository is linked to this project. Link one before granting access."));
            java.util.regex.Matcher m = GITHUB_REPO_URL.matcher(
                    link.getRepositoryUrl() == null ? "" : link.getRepositoryUrl().trim());
            if (!m.matches()) {
                throw new BadRequestException(
                        "Linked repository URL is not a GitHub repo URL — can't grant access automatically.");
            }
            String owner = m.group(1);
            String repo = m.group(2);

            GitHubService.AddCollaboratorResult result =
                    gitHubService.addCollaborator(owner, repo, ghUsername);
            invitationId = result.invitationId();
            log.info("[ProjectAssignmentService] GitHub collaborator-add op={} invitationId={} for assignment={}",
                    result.op(), invitationId, assignmentId);
        } else {
            log.info("[ProjectAssignmentService] GitHub App not configured — recording out-of-band grant "
                    + "for assignment={}", assignmentId);
        }

        a.setAccessGranted(Boolean.TRUE);
        a.setAccessGrantedAt(java.time.Instant.now());
        a.setAccessGrantedById(actor.getId());
        if (invitationId != null) {
            a.setGithubInvitationId(invitationId);
        }
        projectAssignmentRepository.save(a);
        log.info("[ProjectAssignmentService] access granted assignment={} by={} invitationId={}",
                assignmentId, actor.getId(), invitationId);

        // Tier A — interns frequently miss GitHub's own invitation email and
        // then hit a 400 on startAssignment. Send a Skyzen-side internal-mail
        // confirmation pointing them at the GitHub invitation. notifyIntern
        // gates on ACTIVE + mailbox ACTIVATED, so it skips when the intern
        // isn't yet at that point.
        try {
            Project p = projectRepository.findById(a.getProjectId()).orElse(null);
            String projectTitle = p != null
                    ? (p.getName() != null ? p.getName()
                        : (p.getTitle() != null ? p.getTitle() : "your project"))
                    : "your project";
            String actorPhrase = actor != null && actor.getFullName() != null
                    && !actor.getFullName().isBlank()
                    ? actor.getFullName() + ", your Trainer,"
                    : "Your Trainer";
            String subject = "Repo access granted by your Trainer — '" + projectTitle + "'";
            String plain = "Hi,\n\n" + actorPhrase + " has granted you GitHub repository "
                    + "access for the project: \"" + projectTitle + "\"."
                    + "\n\nAccept the GitHub invitation in your inbox (subject usually "
                    + "starts with \"@<your GitHub handle> invited you\") before you "
                    + "start the assignment from /careers/intern/projects."
                    + "\n\n— Anvi Corp";
            internNotifications.notifyIntern(a.getInternId(), subject, plain, null);
        } catch (Exception ex) {
            log.warn("[ProjectAssignmentService] access-granted intern-mail failed (non-fatal) assignment={}: {}",
                    assignmentId, ex.getMessage());
        }
        return mapWithGraph(List.of(a)).get(0);
    }

    @Transactional
    public ProjectAssignmentResponse revokeAccessGranted(UUID assignmentId, User actor) {
        ensureStaff(actor);
        ProjectAssignment a = loadAssignment(assignmentId);
        a.setAccessGranted(Boolean.FALSE);
        a.setAccessGrantedAt(null);
        a.setAccessGrantedById(null);
        projectAssignmentRepository.save(a);
        log.info("[ProjectAssignmentService] access revoked assignment={} by={}",
                assignmentId, actor.getId());
        return mapWithGraph(List.of(a)).get(0);
    }

    @Transactional
    public ProjectAssignmentResponse startAssignment(UUID assignmentId, User actor) {
        ProjectAssignment a = loadAssignment(assignmentId);
        ensureOwningIntern(a, actor);
        if (a.getStatus() != ProjectAssignmentStatus.ASSIGNED) {
            throw new BadRequestException(
                    "Cannot start assignment in status " + a.getStatus());
        }
        User self = userRepository.findById(actor.getId()).orElse(actor);
        if (self.getGithubUsername() == null || self.getGithubUsername().isBlank()) {
            throw new BadRequestException(
                    "Please provide your GitHub username first.");
        }
        // Repo-access gate removed — intern no longer waits for trainer
        // to flip {@code accessGranted=true} before starting. Trainer
        // still invites on GitHub out-of-band (that's the real credential
        // path); the {@code accessGranted} column stays on the entity as
        // an advisory signal (trainer UI still displays it, and the
        // {@code markAccessGranted} endpoint still writes it for
        // reporting) but does not block progression here.
        a.setStatus(ProjectAssignmentStatus.IN_PROGRESS);
        a.setStartedAt(java.time.Instant.now());
        projectAssignmentRepository.save(a);
        log.info("[ProjectAssignmentService] assignment={} intern={} started",
                assignmentId, actor.getId());
        return mapWithGraph(List.of(a)).get(0);
    }

    /**
     * Intern submits (or re-submits) work for an assignment. Accepts
     * deliverable links + free-text notes; creates a {@link ProjectSubmission}
     * history row so the trainer's Pending Reviews queue (which reads
     * from {@code project_submissions}) picks it up, stamps the
     * assignment row, and publishes a {@link ProjectSubmittedEvent} so
     * the trainer is notified after commit.
     *
     * <p>Re-submission is allowed when the assignment is back in
     * {@code IN_PROGRESS} (returned-for-revisions) OR when the prior
     * submission carries {@code trainer_decision=REQUEST_REVISION}. The
     * version on the new {@code ProjectSubmission} row is the prior max
     * plus one — matches the trainer-review service's expectations.</p>
     */
    @Transactional
    public ProjectAssignmentResponse submitAssignment(
            UUID assignmentId, String submissionNotes,
            List<String> deliverableLinks,
            UUID attachmentDocumentId,
            User actor) {
        ProjectAssignment a = loadAssignment(assignmentId);
        ensureOwningIntern(a, actor);
        ensureWeeklyReportingCaughtUp(actor);

        List<String> validatedLinks = validateLinks(deliverableLinks);
        String trimmedNotes = submissionNotes != null && !submissionNotes.isBlank()
                ? submissionNotes.trim() : null;
        if (validatedLinks.isEmpty() && trimmedNotes == null && attachmentDocumentId == null) {
            throw new BadRequestException(
                    "Submission requires at least one deliverable link, a file, or notes.");
        }
        // If the intern points at an attachment doc, verify it exists +
        // hasn't been soft-deleted. We don't check owner strictly — the
        // multipart endpoint that mints the row is intern-only and
        // scoped to the same assignment, so a valid documentId here
        // implies ownership.
        if (attachmentDocumentId != null) {
            documentRepository.findById(attachmentDocumentId).ifPresentOrElse(
                    doc -> {
                        if (doc.getDeletedAt() != null) {
                            throw new BadRequestException(
                                    "Attachment document is no longer available.");
                        }
                    },
                    () -> {
                        throw new BadRequestException(
                                "Attachment document not found: " + attachmentDocumentId);
                    });
        }

        // Re-submission gate: allow when (a) intern is still IN_PROGRESS
        // (first submit, or trainer/RM returned-for-revisions via the
        // assignment endpoint), (b) status is SUBMITTED but the latest
        // ProjectSubmission carries REQUEST_REVISION (trainer review
        // pipe). Anything else (COMPLETED, TECH_APPROVED, etc.) is
        // locked.
        List<ProjectSubmission> history = projectSubmissionRepository
                .findByProjectIdOrderBySubmittedAtDesc(a.getProjectId());
        ProjectSubmission previousLatest = history.isEmpty() ? null : history.get(0);
        boolean revisionRequested = previousLatest != null
                && "REQUEST_REVISION".equalsIgnoreCase(previousLatest.getTrainerDecision());
        if (a.getStatus() == ProjectAssignmentStatus.IN_PROGRESS) {
            // first submit or resubmit after assignment-level return
        } else if (a.getStatus() == ProjectAssignmentStatus.SUBMITTED && revisionRequested) {
            // resubmit after trainer requested revisions on the submission row
        } else if (a.getStatus() == ProjectAssignmentStatus.ASSIGNED) {
            throw new BadRequestException(
                    "Start the project first — submission only allowed after the intern accepts the assignment.");
        } else if (a.getStatus() == ProjectAssignmentStatus.SUBMITTED) {
            throw new BadRequestException(
                    "Project is already submitted and awaiting trainer review.");
        } else {
            throw new BadRequestException(
                    "Cannot submit in status " + a.getStatus() + ".");
        }

        java.time.Instant now = java.time.Instant.now();
        Project project = projectRepository.findById(a.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + a.getProjectId()));
        int nextVersion = history.stream()
                .mapToInt(s -> s.getVersion() != null ? s.getVersion() : 1)
                .max().orElse(0) + 1;
        ProjectSubmission sub = ProjectSubmission.builder()
                .project(project)
                .description(trimmedNotes)
                .linksJson(serializeLinks(validatedLinks))
                .attachmentDocumentId(attachmentDocumentId)
                .submittedAt(now)
                .version(nextVersion)
                .build();
        sub = projectSubmissionRepository.save(sub);

        a.setStatus(ProjectAssignmentStatus.SUBMITTED);
        a.setSubmittedAt(now);
        if (trimmedNotes != null) a.setSubmissionNotes(trimmedNotes);
        projectAssignmentRepository.save(a);

        UUID trainerUserId = resolveTrainerUserId(project, a);
        try {
            eventPublisher.publishEvent(new ProjectSubmittedEvent(
                    a.getId(), project.getId(), sub.getId(),
                    actor.getId(), trainerUserId,
                    project.getName() != null ? project.getName() : project.getTitle(),
                    nextVersion));
        } catch (Exception e) {
            log.warn("[ProjectAssignmentService] project-submitted event publish failed (non-fatal): {}",
                    e.getMessage());
        }
        log.info("[ProjectAssignmentService] assignment={} intern={} submitted v{} (links={}, hasNotes={})",
                assignmentId, actor.getId(), nextVersion,
                validatedLinks.size(), trimmedNotes != null);
        return mapWithGraph(List.of(a)).get(0);
    }

    /**
     * Validate each non-blank link parses as an absolute URL. Returns a
     * cleaned, de-duplicated list preserving input order. Empty / null
     * input → empty list (not an error — notes-only submissions are
     * allowed downstream).
     */
    private List<String> validateLinks(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String link : raw) {
            if (link == null) continue;
            String trimmed = link.trim();
            if (trimmed.isEmpty()) continue;
            try {
                java.net.URI uri = new java.net.URI(trimmed);
                if (!uri.isAbsolute()
                        || uri.getScheme() == null
                        || uri.getHost() == null
                        || !(uri.getScheme().equalsIgnoreCase("http")
                            || uri.getScheme().equalsIgnoreCase("https"))) {
                    throw new BadRequestException(
                            "Deliverable link must be an absolute http/https URL: '"
                                    + trimmed + "'");
                }
            } catch (java.net.URISyntaxException e) {
                throw new BadRequestException(
                        "Deliverable link is not a valid URL: '" + trimmed + "'");
            }
            out.add(trimmed);
        }
        return new ArrayList<>(out);
    }

    private String serializeLinks(List<String> links) {
        if (links == null || links.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(links);
        } catch (Exception e) {
            log.warn("[ProjectAssignmentService] serializeLinks failed (non-fatal): {}",
                    e.getMessage());
            return null;
        }
    }

    private List<String> deserializeLinks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.debug("deserializeLinks failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Resolve which Trainer to notify on submission. Preferred path:
     * Project.internLifecycleId → InternLifecycle.trainerId. Falls back
     * to ProjectAssignment.assignedById (the TE who created the
     * assignment) when the lifecycle linkage isn't populated.
     */
    private UUID resolveTrainerUserId(Project project, ProjectAssignment a) {
        try {
            if (project.getInternLifecycleId() != null) {
                UUID t = internLifecycleRepository.findById(project.getInternLifecycleId())
                        .map(il -> il.getTrainerId())
                        .orElse(null);
                if (t != null) return t;
            }
        } catch (Exception e) {
            log.debug("trainer lookup via lifecycle failed (non-fatal): {}", e.getMessage());
        }
        return a.getAssignedById();
    }

    // ── Reviewer lifecycle (TE + RM on ProjectAssignment) ──────────────────
    //
    // Mirrors the legacy ProjectWorkflowService state machine on the
    // assignment row. Status transitions:
    //   SUBMITTED      → TECH_APPROVED   (TECHNICAL_EVALUATOR)
    //   SUBMITTED      → IN_PROGRESS     (return for revisions; TE)
    //   TECH_APPROVED  → PENDING_VIVA    (REPORTING_MANAGER)
    //   TECH_APPROVED  → COMPLETED       (RM viva-skip close)
    //   TECH_APPROVED  → IN_PROGRESS     (RM return)
    //   PENDING_VIVA   → COMPLETED       (RM sign-off)
    //   PENDING_VIVA   → IN_PROGRESS     (RM return)
    // Each transition writes a structured info log; dedicated audit-row
    // unification with the legacy ProjectWorkflowService trail is deferred.

    @Transactional
    public ProjectAssignmentResponse techApprove(UUID assignmentId, User actor) {
        ensureStaff(actor);
        ProjectAssignment a = loadAssignment(assignmentId);
        if (a.getStatus() == ProjectAssignmentStatus.TECH_APPROVED) {
            return mapWithGraph(List.of(a)).get(0);
        }
        if (a.getStatus() != ProjectAssignmentStatus.SUBMITTED) {
            throw new BadRequestException(
                    "Tech approval requires a SUBMITTED assignment (current: "
                            + a.getStatus() + ")");
        }
        a.setStatus(ProjectAssignmentStatus.TECH_APPROVED);
        projectAssignmentRepository.save(a);
        log.info("[ProjectAssignmentService] tech-approved assignment={} by={}",
                assignmentId, actor.getId());
        return mapWithGraph(List.of(a)).get(0);
    }

    @Transactional
    public ProjectAssignmentResponse returnForRevisions(
            UUID assignmentId, String reason, User actor) {
        ensureReviewerForReturn(actor);
        ProjectAssignment a = loadAssignment(assignmentId);
        ProjectAssignmentStatus from = a.getStatus();
        if (from != ProjectAssignmentStatus.SUBMITTED
                && from != ProjectAssignmentStatus.TECH_APPROVED
                && from != ProjectAssignmentStatus.PENDING_VIVA) {
            throw new BadRequestException(
                    "Can't return an assignment in status " + from);
        }
        if (reason == null || reason.trim().length() < 10) {
            throw new BadRequestException(
                    "A reason of at least 10 characters is required.");
        }
        a.setStatus(ProjectAssignmentStatus.IN_PROGRESS);
        String prior = a.getSubmissionNotes();
        String stamp = "[Returned by " + (actor.getFullName() != null
                ? actor.getFullName() : actor.getEmail()) + "] " + reason.trim();
        a.setSubmissionNotes(prior == null || prior.isBlank()
                ? stamp
                : prior + "\n\n" + stamp);
        projectAssignmentRepository.save(a);
        log.info("[ProjectAssignmentService] returned assignment={} from={} by={}",
                assignmentId, from, actor.getId());
        return mapWithGraph(List.of(a)).get(0);
    }

    @Transactional
    public ProjectAssignmentResponse markPendingViva(UUID assignmentId, User actor) {
        ensureReportingManager(actor);
        ProjectAssignment a = loadAssignment(assignmentId);
        if (a.getStatus() == ProjectAssignmentStatus.PENDING_VIVA) {
            return mapWithGraph(List.of(a)).get(0);
        }
        if (a.getStatus() != ProjectAssignmentStatus.TECH_APPROVED) {
            throw new BadRequestException(
                    "Mark-pending-viva requires TECH_APPROVED (current: "
                            + a.getStatus() + ")");
        }
        a.setStatus(ProjectAssignmentStatus.PENDING_VIVA);
        projectAssignmentRepository.save(a);
        log.info("[ProjectAssignmentService] marked-pending-viva assignment={} by={}",
                assignmentId, actor.getId());
        return mapWithGraph(List.of(a)).get(0);
    }

    @Transactional
    public ProjectAssignmentResponse completeAfterViva(UUID assignmentId, User actor) {
        ensureReportingManager(actor);
        ProjectAssignment a = loadAssignment(assignmentId);
        if (a.getStatus() == ProjectAssignmentStatus.COMPLETED) {
            return mapWithGraph(List.of(a)).get(0);
        }
        if (a.getStatus() != ProjectAssignmentStatus.PENDING_VIVA
                && a.getStatus() != ProjectAssignmentStatus.TECH_APPROVED) {
            throw new BadRequestException(
                    "Complete-after-viva requires PENDING_VIVA or TECH_APPROVED (current: "
                            + a.getStatus() + ")");
        }
        a.setStatus(ProjectAssignmentStatus.COMPLETED);
        projectAssignmentRepository.save(a);
        log.info("[ProjectAssignmentService] completed-after-viva assignment={} by={}",
                assignmentId, actor.getId());
        return mapWithGraph(List.of(a)).get(0);
    }

    /**
     * Status-filtered list for the TE submitted queue + the RM viva queue.
     * Pure read; role check happens on the controller endpoints.
     */
    @Transactional(readOnly = true)
    public List<ProjectAssignmentResponse> listByStatuses(
            List<ProjectAssignmentStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return List.of();
        List<ProjectAssignment> rows = projectAssignmentRepository
                .findByStatusInOrderByUpdatedAtDesc(statuses);
        return mapWithGraph(rows);
    }

    private ProjectAssignment loadAssignment(UUID id) {
        return projectAssignmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assignment not found: " + id));
    }

    private static void ensureStaff(User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required");
        if (actor.getRoles() == null
                || (!actor.getRoles().contains(UserRole.TRAINER)
                    && !actor.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException(
                    "Only TECHNICAL_EVALUATOR or SUPER_ADMIN may perform this action.");
        }
    }

    private static void ensureReportingManager(User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required");
        if (actor.getRoles() == null
                || (!actor.getRoles().contains(UserRole.REPORTING_MANAGER)
                    && !actor.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException(
                    "Only REPORTING_MANAGER or SUPER_ADMIN may perform this action.");
        }
    }

    private static void ensureReviewerForReturn(User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required");
        if (actor.getRoles() == null
                || (!actor.getRoles().contains(UserRole.TRAINER)
                    && !actor.getRoles().contains(UserRole.REPORTING_MANAGER)
                    && !actor.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException(
                    "Only TECHNICAL_EVALUATOR / REPORTING_MANAGER / SUPER_ADMIN "
                            + "may return an assignment for revisions.");
        }
    }

    private static void ensureOwningIntern(ProjectAssignment a, User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required");
        if (!a.getInternId().equals(actor.getId())) {
            throw new ForbiddenException(
                    "Only the assigned intern may perform this action.");
        }
    }

    /**
     * Weekly-reporting-precedes-project-submit gate. An intern cannot submit a
     * project until they are caught up on their weekly reports AND timesheets
     * for every completed work-week between their engagement start and the
     * Monday of the current week. A missing row OR a row still in DRAFT for
     * any required week fails the gate. Mirrors the per-week gate on
     * {@link TimesheetService#ensureWeeklyReportSubmitted} but scans the full
     * back-log — the operator's rule is "no project submit until the
     * paperwork is up to date".
     *
     * <p>Silently passes when the intern has no candidate row, no active
     * engagement, no resolvable start date, or hasn't yet completed a full
     * week since starting. Those edge cases would over-block genuine
     * pre-onboarding intern flows and aren't the target of this gate.</p>
     */
    private void ensureWeeklyReportingCaughtUp(User actor) {
        Candidate intern = candidateRepository.findByUserId(actor.getId()).orElse(null);
        if (intern == null) return;

        Engagement engagement = engagementService
                .resolveActiveForCandidate(intern.getId())
                .orElse(null);
        if (engagement == null) return;

        LocalDate startDate = engagement.getActualStartDate() != null
                ? engagement.getActualStartDate()
                : engagement.getPlannedStartDate();
        if (startDate == null) return;

        LocalDate firstMonday = startDate
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate currentMonday = LocalDate.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // Only require the paperwork for weeks that have fully closed.
        // The current in-progress week isn't gate-worthy — the intern is
        // still working on it.
        LocalDate lastRequired = currentMonday.minusWeeks(1);
        if (lastRequired.isBefore(firstMonday)) return;

        for (LocalDate weekStart = firstMonday;
             !weekStart.isAfter(lastRequired);
             weekStart = weekStart.plusWeeks(1)) {
            boolean reportOk = weeklyReportRepository
                    .findByInternIdAndWeekStart(intern.getId(), weekStart)
                    .map(r -> r.getStatus() != null
                            && r.getStatus() != WeeklyReportStatus.DRAFT)
                    .orElse(false);
            if (!reportOk) {
                throw new BadRequestException(
                        "Submit your weekly report for week of " + weekStart
                                + " before you can submit a project.");
            }
            boolean timesheetOk = timesheetRepository
                    .findByInternIdAndWeekStart(intern.getId(), weekStart)
                    .map(t -> t.getStatus() != null
                            && t.getStatus() != TimesheetStatus.DRAFT)
                    .orElse(false);
            if (!timesheetOk) {
                throw new BadRequestException(
                        "Submit your timesheet for week of " + weekStart
                                + " before you can submit a project.");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectAssignmentResponse> listForIntern(UUID internId) {
        List<ProjectAssignment> rows = projectAssignmentRepository
                .findByInternIdOrderByAssignmentDateDescCreatedAtDesc(internId);
        return mapWithGraph(rows);
    }

    @Transactional(readOnly = true)
    public List<ProjectAssignmentResponse> listForProject(UUID projectId) {
        List<ProjectAssignment> rows = projectAssignmentRepository
                .findByProjectIdOrderByAssignmentDateDescCreatedAtDesc(projectId);
        return mapWithGraph(rows);
    }

    @Transactional(readOnly = true)
    public ProjectAssignmentResponse getAssignment(UUID id, User caller) {
        ProjectAssignment a = projectAssignmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assignment not found: " + id));
        if (caller == null) throw new ForbiddenException("Authentication required");
        boolean isOwner = a.getInternId().equals(caller.getId());
        boolean staffAccess = caller.getRoles() != null
                && (caller.getRoles().contains(UserRole.TRAINER)
                    || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!isOwner && !staffAccess) {
            throw new ForbiddenException("Not authorised to view this assignment");
        }
        return mapWithGraph(List.of(a)).get(0);
    }

    @Transactional(readOnly = true)
    public List<EligibleInternResponse> eligibleInterns() {
        // Engagement-driven roster (post Phase-3 step-10: ApplicationStatus
        // HIRED is deprecated; Engagement.status is the source of truth).
        List<Engagement> active = engagementRepository.findActiveRoster(ELIGIBLE_STATUSES);
        List<EligibleInternResponse> out = new ArrayList<>(active.size());
        Set<UUID> seen = new java.util.HashSet<>();
        for (Engagement e : active) {
            Candidate c = e.getCandidate();
            User u = c != null ? c.getUser() : null;
            if (u == null || !seen.add(u.getId())) continue;
            if (u.getRoles() == null
                    || !u.getRoles().contains(UserRole.INTERN)) continue;
            out.add(new EligibleInternResponse(
                    u.getId(),
                    u.getFullName(),
                    u.getEmail(),
                    u.getGithubUsername(),
                    e.getActualStartDate() != null ? e.getActualStartDate()
                            : e.getPlannedStartDate()));
        }
        out.sort(Comparator.comparing(
                EligibleInternResponse::fullName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return out;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<UUID, Boolean> computeEligibility(List<UUID> internIds) {
        Set<UUID> eligibleSet = eligibleInterns().stream()
                .map(EligibleInternResponse::id)
                .collect(Collectors.toSet());
        Set<UUID> existingUsers = userRepository.findAllById(internIds).stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        Map<UUID, Boolean> out = new HashMap<>();
        for (UUID id : internIds) {
            if (!existingUsers.contains(id)) {
                out.put(id, null); // missing user
            } else {
                out.put(id, eligibleSet.contains(id));
            }
        }
        return out;
    }

    private List<ProjectAssignmentResponse> mapWithGraph(List<ProjectAssignment> rows) {
        if (rows.isEmpty()) return List.of();
        Set<UUID> projectIds = rows.stream().map(ProjectAssignment::getProjectId)
                .collect(Collectors.toSet());
        Set<UUID> userIds = new java.util.HashSet<>();
        for (ProjectAssignment r : rows) {
            userIds.add(r.getInternId());
            userIds.add(r.getAssignedById());
            if (r.getAccessGrantedById() != null) userIds.add(r.getAccessGrantedById());
        }
        Map<UUID, Project> projects = projectRepository.findAllById(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, p -> p));
        // Bulk-fetch latest ProjectSubmission per project so the intern
        // surface can render what they sent + any trainer feedback
        // without N+1 round-trips.
        Map<UUID, ProjectSubmission> latestSubByProject = new HashMap<>();
        Set<UUID> submissionAttachmentIds = new java.util.HashSet<>();
        for (UUID pid : projectIds) {
            List<ProjectSubmission> hist = projectSubmissionRepository
                    .findByProjectIdOrderBySubmittedAtDesc(pid);
            if (!hist.isEmpty()) {
                ProjectSubmission top = hist.get(0);
                latestSubByProject.put(pid, top);
                if (top.getReviewedById() != null) userIds.add(top.getReviewedById());
                if (top.getAttachmentDocumentId() != null) {
                    submissionAttachmentIds.add(top.getAttachmentDocumentId());
                }
            }
        }
        Map<UUID, Document> submissionAttachmentsById = new HashMap<>();
        if (!submissionAttachmentIds.isEmpty()) {
            for (Document d : documentRepository.findAllById(submissionAttachmentIds)) {
                if (d.getDeletedAt() == null) submissionAttachmentsById.put(d.getId(), d);
            }
        }
        // Bulk-fetch the active QaSession per project (SCHEDULED or
        // CONDUCTED only — COMPLETED / RETURNED sessions are history,
        // not a join-now signal). Tracker + dashboard surface the
        // Zoom join link from this.
        Map<UUID, com.anvicorp.api.entity.QaSession> activeQaByProject = new HashMap<>();
        if (!projectIds.isEmpty()) {
            try {
                List<com.anvicorp.api.entity.QaSession> active = qaSessionRepository
                        .findByProjectIdsAndStatusIn(projectIds, List.of(
                                com.anvicorp.api.enums.QaSessionStatus.SCHEDULED,
                                com.anvicorp.api.enums.QaSessionStatus.CONDUCTED));
                for (com.anvicorp.api.entity.QaSession s : active) {
                    UUID pid = s.getProject() != null ? s.getProject().getId() : null;
                    if (pid == null) continue;
                    // Sorted DESC by scheduledAt — first hit wins; later
                    // duplicates (re-schedules) silently skipped here so
                    // the intern only ever sees the newest session.
                    activeQaByProject.putIfAbsent(pid, s);
                    if (s.getScheduledBy() != null) {
                        userIds.add(s.getScheduledBy().getId());
                    }
                }
            } catch (Exception e) {
                log.warn("[ProjectAssignment] bulk QaSession fetch failed "
                        + "(non-fatal): {}", e.getMessage());
            }
        }
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        // Bulk-fetch repository links so per-row mapping doesn't N+1.
        Map<UUID, com.anvicorp.api.entity.ProjectRepositoryLink> repos = new HashMap<>();
        for (UUID pid : projectIds) {
            repositoryLinkRepository.findByProjectId(pid)
                    .ifPresent(l -> repos.put(pid, l));
        }

        // Collect KT-marker user ids so the bulk fetch picks them up too.
        Set<UUID> ktMarkerIds = projects.values().stream()
                .map(Project::getKtMarkedById)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (!ktMarkerIds.isEmpty()) {
            for (User u : userRepository.findAllById(ktMarkerIds)) {
                users.putIfAbsent(u.getId(), u);
            }
        }

        // Bulk-resolve trainer-attached project files. Trainer's attach path
        // stashes the Document UUID into project.resource_links_json as a
        // single-element JSON array (see TrainerProjectAssignmentService.
        // attachProjectFile). Pre-resolve all referenced documents in one
        // query so the per-row mapping doesn't N+1, and drop soft-deleted
        // rows on the way through.
        Map<UUID, List<UUID>> projectToDocIds = new HashMap<>();
        Set<UUID> allDocIds = new java.util.HashSet<>();
        for (Project p : projects.values()) {
            List<UUID> ids = extractResourceDocIds(p.getResourceLinksJson());
            if (!ids.isEmpty()) {
                projectToDocIds.put(p.getId(), ids);
                allDocIds.addAll(ids);
            }
        }
        Map<UUID, Document> docsById = new HashMap<>();
        if (!allDocIds.isEmpty()) {
            for (Document d : documentRepository.findAllById(allDocIds)) {
                if (d.getDeletedAt() == null) docsById.put(d.getId(), d);
            }
        }

        return rows.stream().map(r -> {
            Project p = projects.get(r.getProjectId());
            User intern = users.get(r.getInternId());
            User assignedBy = users.get(r.getAssignedById());
            User accessGrantedBy = r.getAccessGrantedById() != null
                    ? users.get(r.getAccessGrantedById()) : null;
            com.anvicorp.api.entity.ProjectRepositoryLink link = p != null
                    ? repos.get(p.getId()) : null;
            ProjectAssignmentResponse.RepositorySummary repoSummary = link == null
                    ? null
                    : new ProjectAssignmentResponse.RepositorySummary(
                            link.getRepositoryName(), link.getRepositoryUrl());
            // Read remarks from new column, falling back to legacy `notes`
            // for rows persisted before the column was added.
            String remarks = r.getRemarks() != null && !r.getRemarks().isBlank()
                    ? r.getRemarks() : r.getNotes();
            ProjectSubmission top = p != null ? latestSubByProject.get(p.getId()) : null;
            ProjectAssignmentResponse.SubmissionAttachmentRef attachmentRef = null;
            if (top != null && top.getAttachmentDocumentId() != null) {
                Document attachmentDoc = submissionAttachmentsById.get(top.getAttachmentDocumentId());
                if (attachmentDoc != null) {
                    attachmentRef = new ProjectAssignmentResponse.SubmissionAttachmentRef(
                            attachmentDoc.getId(),
                            attachmentDoc.getFileName(),
                            attachmentDoc.getMimeType(),
                            attachmentDoc.getFileSize());
                }
            }
            ProjectAssignmentResponse.LatestSubmission latest = top == null ? null
                    : new ProjectAssignmentResponse.LatestSubmission(
                            top.getId(),
                            top.getVersion(),
                            deserializeLinks(top.getLinksJson()),
                            top.getDescription(),
                            top.getSubmittedAt(),
                            top.getTrainerDecision(),
                            top.getTrainerFeedback(),
                            top.getReviewedAt(),
                            top.getReviewedById() != null
                                    && users.get(top.getReviewedById()) != null
                                    ? users.get(top.getReviewedById()).getFullName()
                                    : null,
                            attachmentRef);
            ProjectAssignmentResponse.KtSummary ktSummary = p == null
                    ? null
                    : buildKtSummary(p, users);
            com.anvicorp.api.entity.QaSession activeQa = p != null
                    ? activeQaByProject.get(p.getId()) : null;
            ProjectAssignmentResponse.QaSummary qaSummary = activeQa == null
                    ? null
                    : buildQaSummary(activeQa, users);
            List<ProjectAssignmentResponse.ProjectFileRef> projectFiles = p == null
                    ? null
                    : toProjectFileRefs(projectToDocIds.get(p.getId()), docsById);
            return new ProjectAssignmentResponse(
                    r.getId(),
                    p == null ? null : new ProjectAssignmentResponse.ProjectRef(
                            p.getId(),
                            p.getName() != null ? p.getName() : p.getTitle(),
                            p.getTechStack(),
                            p.getDifficulty(),
                            p.getDescription(),
                            p.getRequirements(),
                            p.getObjectives(),
                            p.getDeliverables(),
                            p.getInstructions(),
                            p.getExpectedDurationDays(),
                            p.getStartDate(),
                            p.getDueDate(),
                            repoSummary,
                            ktSummary,
                            projectFiles),
                    userRef(intern),
                    userRef(assignedBy),
                    r.getAssignmentDate(),
                    r.getDueDate(),
                    remarks,
                    r.getStatus(),
                    r.getAccessGranted(),
                    r.getAccessGrantedAt(),
                    userRef(accessGrantedBy),
                    r.getStartedAt(),
                    r.getSubmittedAt(),
                    r.getSubmissionNotes(),
                    latest,
                    qaSummary,
                    r.getCreatedAt(),
                    r.getUpdatedAt()
            );
        }).toList();
    }

    /**
     * Intern-safe projection of the active QaSession. Mirrors the
     * KtSummary builder — intern gets join URL + time + duration +
     * who scheduled, NEVER the host start URL (that field is host-only).
     */
    private static ProjectAssignmentResponse.QaSummary buildQaSummary(
            com.anvicorp.api.entity.QaSession s, Map<UUID, User> users) {
        if (s == null) return null;
        String scheduledByName = null;
        if (s.getScheduledBy() != null) {
            User u = users.get(s.getScheduledBy().getId());
            scheduledByName = u != null ? u.getFullName()
                    : s.getScheduledBy().getFullName();
        }
        return new ProjectAssignmentResponse.QaSummary(
                s.getId(),
                s.getStatus() != null ? s.getStatus().name() : null,
                s.getScheduledAt(),
                s.getSessionDurationMinutes(),
                s.getSessionTimezone(),
                s.getMeetingLink(),
                s.getZoomMeetingId(),
                s.getZoomJoinUrl(),
                scheduledByName);
    }

    private static ProjectAssignmentResponse.KtSummary buildKtSummary(Project p, Map<UUID, User> users) {
        // KT only meaningful for assigned projects (the intern's My
        // Projects detail uses this; catalog rows aren't surfaced via
        // the intern endpoint).
        if (p == null) return null;
        String status = p.getKtStatus() != null ? p.getKtStatus() : "NOT_DONE";
        String markedByName = null;
        if (p.getKtMarkedById() != null) {
            User u = users.get(p.getKtMarkedById());
            if (u != null) markedByName = u.getFullName();
        }
        return new ProjectAssignmentResponse.KtSummary(
                status,
                p.getKtCompletedAt(),
                p.getKtMeetingLink(),
                p.getKtNotes(),
                markedByName,
                // Live KT session (Zoom) — intern-safe fields only. The
                // host start URL is deliberately NOT surfaced; intern
                // sees join-only via the existing role-based pattern.
                p.getKtZoomMeetingId(),
                p.getKtZoomJoinUrl(),
                p.getKtScheduledFor(),
                p.getKtDurationMinutes(),
                p.getKtTimezone());
    }

    /**
     * Parse {@code project.resource_links_json} into the list of attached
     * Document UUIDs. The trainer attach path writes a single-element JSON
     * array of UUID strings (see TrainerProjectAssignmentService.
     * attachProjectFile); future multi-file support can extend the array
     * without changing this reader. Returns empty on null / blank / parse
     * errors / shape mismatches — the field is best-effort, not load-bearing.
     */
    private List<UUID> extractResourceDocIds(String resourceLinksJson) {
        if (resourceLinksJson == null || resourceLinksJson.isBlank()) return List.of();
        try {
            List<?> raw = objectMapper.readValue(resourceLinksJson, List.class);
            List<UUID> out = new ArrayList<>(raw.size());
            for (Object o : raw) {
                if (o == null) continue;
                try {
                    out.add(UUID.fromString(o.toString()));
                } catch (IllegalArgumentException ignored) {
                    // legacy entries may be plain URL strings — skip
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<ProjectAssignmentResponse.ProjectFileRef> toProjectFileRefs(
            List<UUID> ids, Map<UUID, Document> docsById) {
        if (ids == null || ids.isEmpty()) return null;
        List<ProjectAssignmentResponse.ProjectFileRef> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Document d = docsById.get(id);
            if (d == null) continue;
            out.add(new ProjectAssignmentResponse.ProjectFileRef(
                    d.getId(), d.getFileName(), d.getMimeType(), d.getFileSize()));
        }
        return out.isEmpty() ? null : out;
    }

    /** Trainer-uploaded project file payload returned to the controller for streaming. */
    public record DownloadedProjectFile(UUID documentId, String fileName, String mimeType, byte[] bytes) {}

    /** Intern's submission-attachment upload — echoes what to POST as the
     *  {@code attachmentDocumentId} on the follow-up {@code /submit} call. */
    public record SubmissionAttachmentUploadResponse(
            UUID documentId, String fileName, String mimeType, Long fileSize) {}

    /** Attachment bytes returned to the controller for streaming.
     *  Emitted by {@link #downloadSubmissionAttachment(UUID, User)}. */
    public record SubmissionAttachmentDownload(
            UUID documentId, String fileName, String mimeType, byte[] bytes) {}

    private static final long SUBMISSION_ATTACHMENT_MAX_BYTES = 50L * 1024 * 1024; // 50 MB
    private static final String SUBMISSION_ATTACHMENT_CATEGORY = "PROJECT_SUBMISSION";
    private static final Set<String> SUBMISSION_ATTACHMENT_ALLOWED_SUFFIXES = Set.of(
            ".pdf", ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
            ".txt", ".md", ".zip", ".png", ".jpg", ".jpeg", ".gif");

    /**
     * Intern uploads a supporting file for their project submission. The
     * document row is minted here so the follow-up {@code /submit} call
     * only needs the returned {@code documentId}. Same through-backend
     * pattern as {@code WeeklyReportService.uploadAttachment} — we don't
     * hand the intern a presigned S3 PUT for this size class.
     *
     * <p>RBAC: only the owning intern of the assignment may upload. The
     * assignment must be in a state where a submission is still allowed
     * (IN_PROGRESS, or SUBMITTED-with-REQUEST_REVISION resubmission
     * window) — no point letting an intern burn storage on a locked
     * assignment.</p>
     */
    @Transactional
    public SubmissionAttachmentUploadResponse uploadSubmissionAttachment(
            UUID assignmentId,
            org.springframework.web.multipart.MultipartFile file,
            User actor) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > SUBMISSION_ATTACHMENT_MAX_BYTES) {
            throw new BadRequestException(
                    "Attachment exceeds " + (SUBMISSION_ATTACHMENT_MAX_BYTES / (1024 * 1024))
                            + " MB limit.");
        }
        String filename = file.getOriginalFilename();
        boolean suffixOk = filename != null
                && SUBMISSION_ATTACHMENT_ALLOWED_SUFFIXES.stream()
                        .anyMatch(s -> filename.toLowerCase().endsWith(s));
        if (!suffixOk) {
            throw new BadRequestException(
                    "Unsupported file type. Allowed: PDF, DOC(X), PPT(X), XLS(X), TXT, MD, ZIP, PNG, JPG, GIF.");
        }
        ProjectAssignment a = loadAssignment(assignmentId);
        ensureOwningIntern(a, actor);
        ProjectAssignmentStatus st = a.getStatus();
        if (st != ProjectAssignmentStatus.IN_PROGRESS
                && st != ProjectAssignmentStatus.SUBMITTED
                && st != ProjectAssignmentStatus.ASSIGNED) {
            throw new BadRequestException(
                    "Cannot upload a submission attachment in status " + st + ".");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new BadRequestException("Could not read uploaded bytes: " + e.getMessage());
        }
        String mime = file.getContentType();
        Document saved = documentVault.saveDocument(
                actor.getId(),
                filename != null ? filename : "submission-attachment.bin",
                mime != null ? mime : "application/octet-stream",
                bytes,
                SUBMISSION_ATTACHMENT_CATEGORY,
                "NORMAL",
                actor.getId());
        log.info("[ProjectAssignmentService] submission-attachment uploaded assignment={} intern={} doc={} size={}",
                assignmentId, actor.getId(), saved.getId(), saved.getFileSize());
        return new SubmissionAttachmentUploadResponse(
                saved.getId(), saved.getFileName(), saved.getMimeType(), saved.getFileSize());
    }

    /**
     * Stream an intern-uploaded submission attachment. RBAC: intern
     * owner of the assignment, the assigned Trainer (any TRAINER role
     * matches — same policy as {@link #downloadProjectFile}), or
     * SUPER_ADMIN. The submission's assignment is resolved via
     * project → assignments; if none match the assignment scope check
     * refuses.
     */
    @Transactional
    public SubmissionAttachmentDownload downloadSubmissionAttachment(
            UUID submissionId, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        ProjectSubmission sub = projectSubmissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Submission not found: " + submissionId));
        UUID docId = sub.getAttachmentDocumentId();
        if (docId == null) {
            throw new ResourceNotFoundException("No attachment on this submission");
        }
        UUID projectId = sub.getProject() != null ? sub.getProject().getId() : null;
        if (projectId == null) {
            throw new ResourceNotFoundException("Submission has no project");
        }
        boolean isStaff = caller.getRoles() != null
                && (caller.getRoles().contains(UserRole.TRAINER)
                    || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        boolean isOwner = false;
        if (!isStaff) {
            // Owner check: at least one assignment on this project belongs
            // to the caller. Interns own their assignments, so this is the
            // narrowest scope check that lets the intern re-download what
            // they just posted.
            List<ProjectAssignment> byProject = projectAssignmentRepository
                    .findByProjectIdOrderByAssignmentDateDescCreatedAtDesc(projectId);
            for (ProjectAssignment a : byProject) {
                if (a.getInternId() != null && a.getInternId().equals(caller.getId())) {
                    isOwner = true;
                    break;
                }
            }
        }
        if (!isStaff && !isOwner) {
            throw new ForbiddenException("Not authorised to download this attachment");
        }
        Document doc = documentVault.loadDocumentNoAuth(docId);
        byte[] bytes = documentVault.readDocumentBytesNoAuth(docId);
        return new SubmissionAttachmentDownload(
                doc.getId(), doc.getFileName(), doc.getMimeType(), bytes);
    }

    /**
     * Download a trainer-uploaded project file for an assignment. The
     * project file's Document row is owned by the trainer, so the vault's
     * standard owner-or-staff RBAC would reject the intern. We instead
     * authorize on the assignment row: the owning intern (or staff) may
     * download. Resolves the Document UUID from
     * {@code project.resource_links_json} (first entry), then reads bytes
     * via {@link DocumentVaultService#readDocumentBytesNoAuth(UUID)}.
     */
    @Transactional
    public DownloadedProjectFile downloadProjectFile(UUID assignmentId, UUID documentId, User caller) {
        ProjectAssignment a = loadAssignment(assignmentId);
        if (caller == null) throw new ForbiddenException("Authentication required");
        boolean isOwner = a.getInternId() != null && a.getInternId().equals(caller.getId());
        boolean isStaff = caller.getRoles() != null
                && (caller.getRoles().contains(UserRole.TRAINER)
                    || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!isOwner && !isStaff) {
            throw new ForbiddenException("Not authorised to download this project's file");
        }
        Project project = projectRepository.findById(a.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + a.getProjectId()));
        List<UUID> docIds = extractResourceDocIds(project.getResourceLinksJson());
        if (docIds.isEmpty()) {
            throw new ResourceNotFoundException("No file attached to this project");
        }
        UUID targetDocId = documentId != null ? documentId : docIds.get(0);
        if (!docIds.contains(targetDocId)) {
            // Guards against passing a doc UUID that belongs to some OTHER
            // project — the assignment scope check above must be the only
            // gate on which document this caller can pull.
            throw new ForbiddenException("Document is not attached to this project");
        }
        Document doc = documentVault.loadDocumentNoAuth(targetDocId);
        byte[] bytes = documentVault.readDocumentBytesNoAuth(targetDocId);
        return new DownloadedProjectFile(doc.getId(), doc.getFileName(), doc.getMimeType(), bytes);
    }

    private static ProjectAssignmentResponse.UserRef userRef(User u) {
        if (u == null) return null;
        return new ProjectAssignmentResponse.UserRef(
                u.getId(), u.getFullName(), u.getEmail(), u.getGithubUsername());
    }

    @SuppressWarnings("unused")
    private static LocalDate todayLocal() { return LocalDate.now(); }
}
