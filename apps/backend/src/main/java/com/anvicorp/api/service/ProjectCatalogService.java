package com.anvicorp.api.service;

import com.anvicorp.api.dto.project.catalog.CatalogProjectResponse;
import com.anvicorp.api.dto.project.catalog.CreateCatalogProjectRequest;
import com.anvicorp.api.dto.project.catalog.KtMarkRequest;
import com.anvicorp.api.dto.project.catalog.KtScheduleRequest;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.notification.SchedulerMeetingEmailSender;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.trainer.TrainerScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Catalog half of the Project Assignment module. Creates and reads
 * catalog Project rows. Coexists with the legacy single-allocation
 * ProjectService — catalog projects leave {@code intern_id / status}
 * NULL; legacy projects continue to use those columns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectCatalogService {

    private final ProjectRepository projectRepository;
    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final UserRepository userRepository;
    private final com.anvicorp.api.repository.ProjectRepositoryLinkRepository
            repositoryLinkRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final UserNotificationDispatcher userNotificationDispatcher;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;
    private final TrainerScopeGuard trainerScopeGuard;
    private final MeetingProvider meetingProvider;
    private final SchedulerMeetingEmailSender schedulerEmail;

    @Transactional
    public CatalogProjectResponse createCatalogProject(
            CreateCatalogProjectRequest req, User actor) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (req.endDate() != null && req.startDate() != null
                && req.endDate().isBefore(req.startDate())) {
            throw new BadRequestException("endDate must be on or after startDate");
        }

        Project project = new Project();
        project.setName(req.name().trim());
        project.setDescription(req.description());
        project.setRequirements(req.requirements());
        project.setObjectives(req.objectives());
        project.setTechStack(req.techStack());
        project.setExpectedDurationDays(req.expectedDurationDays());
        project.setDeliverables(req.deliverables());
        project.setDifficulty(req.difficulty());
        project.setInstructions(req.instructions());
        project.setStartDate(req.startDate());
        project.setCreatedById(actor.getId());
        // Catalog rows mirror the name into the legacy title column so legacy
        // readers (workspace, project board) still display something useful
        // until the columns are unified.
        project.setTitle(req.name().trim());
        project.setProgressPct(0);
        if (project.getCreatedAt() == null) {
            project.setCreatedAt(Instant.now());
        }
        project = projectRepository.save(project);

        log.info("[ProjectCatalogService] catalog project created id={} by user={}",
                project.getId(),
                actor.getId());
        return toResponse(project, actor);
    }

    /**
     * Trainer-only action: mark the KT session done for an assigned
     * monthly project. Optional meeting link (validated as absolute
     * http/https URL) + free-text notes. Idempotent on re-mark (returns
     * the row with the existing KT stamp untouched — keeps the original
     * completion timestamp authoritative).
     *
     * <p>Owner check: the project must be assigned (intern_lifecycle_id
     * non-null) and the caller must be either SUPER_ADMIN or the
     * Trainer recorded on that {@link InternLifecycle} (matches Phase 5
     * trainer-scoped patterns).</p>
     */
    @Transactional
    public CatalogProjectResponse markKtDone(UUID projectId, KtMarkRequest req, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        if (project.getInternLifecycleId() == null) {
            throw new ConflictException(
                    "KT cannot be marked — this project isn't assigned to an intern yet.");
        }
        ensureTrainerForProject(project, caller);

        String meetingLink = req != null ? trimToNull(req.meetingLink()) : null;
        String notes = req != null ? trimToNull(req.notes()) : null;
        if (meetingLink != null) validateUrl(meetingLink);

        boolean alreadyDone = "DONE".equalsIgnoreCase(project.getKtStatus());
        if (!alreadyDone) {
            project.setKtStatus("DONE");
            project.setKtCompletedAt(Instant.now());
            project.setKtMarkedById(caller.getId());
        }
        // Always allow updating the link/notes — Trainer may add the recording
        // link or polish the topic summary after the live call.
        if (meetingLink != null) project.setKtMeetingLink(meetingLink);
        if (notes != null) project.setKtNotes(notes);
        project = projectRepository.save(project);

        if (!alreadyDone) {
            dispatchKtDoneNotification(project);
        }
        log.info("[ProjectCatalogService] KT done project={} by={} (firstMark={})",
                project.getId(), caller.getId(), !alreadyDone);
        User creator = resolveCreator(project.getCreatedById());
        return toResponse(project, creator);
    }

    /**
     * Schedule a live KT session: creates a Zoom meeting via the
     * shared {@link MeetingProvider} and stores host + join URLs on
     * the project, then dispatches a KT_SCHEDULED notification fan-out
     * (intern internal-mail + in-app, plus a host-link email to the
     * trainer). Mirrors {@code WeeklyMeetingService.schedule} so KT and
     * weekly meetings behave identically end-to-end.
     *
     * <p>Independent of {@link #markKtDone} — the trainer can mark KT
     * done at any time without scheduling a session first (trust the
     * trainer per the brief). Re-scheduling overwrites the previous
     * session's links (previous Zoom is left orphaned; rare enough we
     * skip a delete + retry handshake).</p>
     */
    @Transactional
    public CatalogProjectResponse scheduleKt(UUID projectId, KtScheduleRequest req, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (req == null || req.scheduledFor() == null || req.scheduledFor().isBlank()) {
            throw new BadRequestException("scheduledFor is required");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        if (project.getInternLifecycleId() == null) {
            throw new ConflictException(
                    "KT cannot be scheduled — this project isn't assigned to an intern yet.");
        }
        ensureTrainerForProject(project, caller);

        Instant scheduledFor;
        try {
            scheduledFor = Instant.parse(req.scheduledFor().trim());
        } catch (Exception e) {
            throw new BadRequestException(
                    "scheduledFor must be an ISO-8601 instant (e.g. 2026-07-15T14:00:00Z)");
        }
        if (scheduledFor.isBefore(Instant.now().minusSeconds(300))) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        int duration = req.durationMinutes() == null ? 30
                : Math.max(15, Math.min(180, req.durationMinutes()));
        String tz = (req.timezone() == null || req.timezone().isBlank())
                ? "UTC" : req.timezone();
        String projectLabel = labelFor(project);
        String topic = (req.topic() != null && !req.topic().isBlank())
                ? req.topic().trim()
                : "KT Session — " + projectLabel;
        String agenda = req.agenda() != null ? req.agenda() : null;

        // Best-effort Zoom create — same pattern as WeeklyMeetingService.
        String zoomId = null;
        String joinUrl = null;
        String startUrl = null;
        if (meetingProvider.isReady()) {
            try {
                String hostId = caller.getZoomEmail() != null && !caller.getZoomEmail().isBlank()
                        ? caller.getZoomEmail() : "me";
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(hostId, topic, scheduledFor, duration, tz, agenda));
                zoomId = z.providerMeetingId();
                joinUrl = z.joinUrl();
                startUrl = z.startUrl();
                log.info("[KtSchedule] {} meeting created id={} for project={}",
                        meetingProvider.providerName(), zoomId, project.getId());
            } catch (Exception e) {
                log.warn("[KtSchedule] {} create failed (non-fatal); KT row still persists: {}",
                        meetingProvider.providerName(), e.getMessage());
            }
        }

        project.setKtZoomMeetingId(zoomId);
        project.setKtZoomJoinUrl(joinUrl);
        project.setKtZoomStartUrl(startUrl);
        project.setKtScheduledFor(scheduledFor);
        project.setKtDurationMinutes(duration);
        project.setKtTimezone(tz);
        project = projectRepository.save(project);

        dispatchKtScheduledNotification(project, caller, topic);
        log.info("[ProjectCatalogService] KT scheduled project={} for {} by={}",
                project.getId(), scheduledFor, caller.getId());
        User creator = resolveCreator(project.getCreatedById());
        return toResponse(project, creator);
    }

    /**
     * Fan-out for KT_SCHEDULED: intern gets in-app + internal mail with
     * the join button; trainer gets the host-link email (mirrors the
     * weekly-meeting pattern).
     */
    private void dispatchKtScheduledNotification(Project project, User trainer, String topic) {
        try {
            UUID lifecycleId = project.getInternLifecycleId();
            if (lifecycleId == null) return;
            InternLifecycle lc = internLifecycleRepository.findById(lifecycleId).orElse(null);
            if (lc == null || lc.getUserId() == null) return;
            User intern = userRepository.findById(lc.getUserId()).orElse(null);
            if (intern == null) return;

            String projectLabel = labelFor(project);
            String when = project.getKtScheduledFor() != null
                    ? project.getKtScheduledFor().toString() : "TBD";
            String tz = project.getKtTimezone() != null ? project.getKtTimezone() : "UTC";

            // Actor + role per Model A: "<Name>, your Trainer, ..."
            String actorPhrase = trainer != null && trainer.getFullName() != null
                    ? trainer.getFullName() + ", your Trainer,"
                    : "Your Trainer";

            // Intern: in-app
            try {
                userNotificationDispatcher.dispatch(intern.getId(), "KT_SCHEDULED",
                        trainer != null ? trainer.getId() : null,
                        "KT session scheduled by your Trainer — " + projectLabel,
                        actorPhrase + " scheduled a KT session for " + projectLabel
                                + " on " + when + " (" + tz + ").",
                        "/careers/intern/projects", true);
            } catch (Exception e) {
                log.warn("[KtSchedule] in-app dispatch failed: {}", e.getMessage());
            }

            // Intern: internal mail (active interns with ACTIVATED mailbox only)
            String body = actorPhrase + " scheduled a KT session for \"" + projectLabel
                    + "\" on " + when + " (" + tz + "), "
                    + (project.getKtDurationMinutes() != null
                        ? project.getKtDurationMinutes() : 30)
                    + " min."
                    + (project.getKtZoomJoinUrl() != null
                        ? "\n\nJoin link: " + project.getKtZoomJoinUrl() : "")
                    + "\n\nOpen your projects: /careers/intern/projects"
                    + "\n\n" + brand.signoff();
            internNotifications.notifyIntern(intern.getId(),
                    com.anvicorp.api.notification.NotificationEventType.KT_SCHEDULED,
                    "KT session scheduled by your Trainer — " + projectLabel, body, null);

            // Trainer: host-link email (mirrors WeeklyMeeting trainer email).
            if (trainer != null && trainer.getEmail() != null && !trainer.getEmail().isBlank()) {
                String participantLabel = "with " + (intern.getFullName() != null
                        ? intern.getFullName() : "the intern");
                schedulerEmail.send(
                        trainer.getEmail(),
                        firstName(trainer),
                        "KT session scheduled",
                        topic != null ? topic : ("KT Session — " + projectLabel),
                        participantLabel,
                        project.getKtScheduledFor(),
                        project.getKtTimezone(),
                        project.getKtZoomJoinUrl(),
                        project.getKtZoomStartUrl(),
                        project.getKtZoomMeetingId());
            }
        } catch (Exception e) {
            log.warn("[ProjectCatalogService] KT-scheduled notify failed (non-fatal): {}",
                    e.getMessage());
        }
    }

    private static String labelFor(Project project) {
        if (project.getName() != null && !project.getName().isBlank()) return project.getName();
        if (project.getTitle() != null && !project.getTitle().isBlank()) return project.getTitle();
        return "your project";
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "there";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "there";
    }

    private void ensureTrainerForProject(Project project, User caller) {
        // Delegate ownership check to the shared TrainerScopeGuard so KT
        // mark-done and the project-assign endpoints share one model
        // (SUPER_ADMIN bypass, TRAINER required, null-trainer_id fallback).
        InternLifecycle il = project.getInternLifecycleId() == null
                ? null
                : internLifecycleRepository.findById(project.getInternLifecycleId())
                        .orElse(null);
        trainerScopeGuard.requireTrainerOwnership(il, caller);
    }

    private void dispatchKtDoneNotification(Project project) {
        try {
            UUID lifecycleId = project.getInternLifecycleId();
            if (lifecycleId == null) return;
            UUID internUserId = internLifecycleRepository.findById(lifecycleId)
                    .map(InternLifecycle::getUserId).orElse(null);
            if (internUserId == null) return;
            String projectLabel = project.getName() != null && !project.getName().isBlank()
                    ? project.getName()
                    : (project.getTitle() != null ? project.getTitle() : "your project");
            String title = "KT session marked complete";
            String body = "Your Trainer marked KT done for " + projectLabel + "."
                    + (project.getKtMeetingLink() != null && !project.getKtMeetingLink().isBlank()
                        ? " A meeting link is available on the project page." : "");
            userNotificationDispatcher.dispatch(internUserId, "KT_DONE",
                    project.getKtMarkedById(), title, body,
                    "/careers/intern/projects", false);
            // Phase: Employee internal-mail — also land this event in the
            // intern's company mailbox. The helper short-circuits when the
            // intern isn't activeStatus='ACTIVE' OR doesn't have a
            // mailHandoverState=ACTIVATED mailbox; safe to call always.
            //
            // Actor + role per Model A: "<Name>, your Trainer, ...". The
            // trainer is the user who marked KT done (project.kt_marked_by_id).
            User actor = project.getKtMarkedById() != null
                    ? userRepository.findById(project.getKtMarkedById()).orElse(null)
                    : null;
            String actorPhrase = actor != null && actor.getFullName() != null
                    ? actor.getFullName() + ", your Trainer,"
                    : "Your Trainer";
            String mailSubject = "KT marked complete by your Trainer — " + projectLabel;
            String mailPlain = actorPhrase + " marked KT done for "
                    + projectLabel + "."
                    + (project.getKtMeetingLink() != null && !project.getKtMeetingLink().isBlank()
                        ? " The KT meeting link is available on the project page." : "")
                    + "\n\nOpen your projects: /careers/intern/projects\n\n" + brand.signoff();
            internNotifications.notifyIntern(internUserId,
                    com.anvicorp.api.notification.NotificationEventType.KT_COMPLETED,
                    mailSubject, mailPlain, null);
        } catch (Exception e) {
            log.warn("[ProjectCatalogService] KT-done notify failed (non-fatal): {}",
                    e.getMessage());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static void validateUrl(String s) {
        try {
            java.net.URI uri = new java.net.URI(s);
            if (!uri.isAbsolute() || uri.getScheme() == null || uri.getHost() == null
                    || !(uri.getScheme().equalsIgnoreCase("http")
                        || uri.getScheme().equalsIgnoreCase("https"))) {
                throw new BadRequestException(
                        "KT meeting link must be an absolute http/https URL: '" + s + "'");
            }
        } catch (java.net.URISyntaxException e) {
            throw new BadRequestException(
                    "KT meeting link is not a valid URL: '" + s + "'");
        }
    }

    @Transactional(readOnly = true)
    public CatalogProjectResponse getCatalogProject(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + id));
        User creator = resolveCreator(project.getCreatedById());
        return toResponseWithCount(project, creator);
    }

    @Transactional(readOnly = true)
    public List<CatalogProjectResponse> listCreatedBy(UUID userId) {
        List<Project> projects = projectRepository.findByCreatedByIdOrderByCreatedAtDesc(userId);
        return mapWithCounts(projects);
    }

    @Transactional(readOnly = true)
    public List<CatalogProjectResponse> listAllCatalog() {
        // Catalog projects are the ones with a created_by_id set; legacy
        // single-allocation rows leave it null. Sort newest first.
        List<Project> projects = projectRepository.findByCreatedByIdNotNullOrderByCreatedAtDesc();
        return mapWithCounts(projects);
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private List<CatalogProjectResponse> mapWithCounts(List<Project> projects) {
        if (projects.isEmpty()) return List.of();
        Set<UUID> creatorIds = projects.stream()
                .map(Project::getCreatedById)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, User> users = userRepository.findAllById(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return projects.stream()
                .sorted(Comparator.comparing(
                        Project::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(p -> toResponseWithCount(p, users.get(p.getCreatedById())))
                .toList();
    }

    // ── Repository link / update ────────────────────────────────────────────

    @Transactional
    public CatalogProjectResponse linkRepository(
            UUID projectId, String repositoryName, String repositoryUrl, User actor) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        if (repositoryLinkRepository.existsByProjectId(projectId)) {
            throw new BadRequestException(
                    "Project already has a repository linked. Use PUT to update.");
        }
        com.anvicorp.api.entity.ProjectRepositoryLink link =
                com.anvicorp.api.entity.ProjectRepositoryLink.builder()
                .projectId(projectId)
                .repositoryName(trim(repositoryName))
                .repositoryUrl(normaliseUrl(repositoryUrl))
                .linkedById(actor.getId())
                .build();
        repositoryLinkRepository.save(link);
        log.info("[ProjectCatalogService] repository linked to project={} url={} by={}",
                projectId, link.getRepositoryUrl(), actor.getId());
        return toResponseWithCount(project, resolveCreator(project.getCreatedById()));
    }

    @Transactional
    public CatalogProjectResponse updateRepository(
            UUID projectId, String repositoryName, String repositoryUrl, User actor) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        com.anvicorp.api.entity.ProjectRepositoryLink link =
                repositoryLinkRepository.findByProjectId(projectId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "No repository is linked to this project."));
        link.setRepositoryName(trim(repositoryName));
        link.setRepositoryUrl(normaliseUrl(repositoryUrl));
        link.setLinkedById(actor.getId());
        repositoryLinkRepository.save(link);
        log.info("[ProjectCatalogService] repository updated for project={} url={} by={}",
                projectId, link.getRepositoryUrl(), actor.getId());
        return toResponseWithCount(project, resolveCreator(project.getCreatedById()));
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normaliseUrl(String url) {
        if (url == null) return null;
        String t = url.trim();
        if (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private CatalogProjectResponse toResponse(Project p, User creator) {
        return new CatalogProjectResponse(
                p.getId(),
                p.getName() != null ? p.getName() : p.getTitle(),
                p.getDescription(),
                p.getRequirements(),
                p.getObjectives(),
                p.getTechStack(),
                p.getExpectedDurationDays(),
                p.getDeliverables(),
                p.getDifficulty(),
                p.getInstructions(),
                p.getStartDate(),
                p.getDueDate(),
                creator != null
                        ? new CatalogProjectResponse.UserRef(creator.getId(), creator.getFullName())
                        : null,
                0L,
                null,
                buildKtSummary(p),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    private CatalogProjectResponse.KtSummary buildKtSummary(Project p) {
        // Only surface KT for assigned projects — for catalog rows the
        // intern_lifecycle_id is null and KT is conceptually N/A.
        if (p.getInternLifecycleId() == null) return null;
        String markedByName = null;
        if (p.getKtMarkedById() != null) {
            User u = userRepository.findById(p.getKtMarkedById()).orElse(null);
            markedByName = u != null ? u.getFullName() : null;
        }
        return new CatalogProjectResponse.KtSummary(
                p.getKtStatus() != null ? p.getKtStatus() : "NOT_DONE",
                p.getKtCompletedAt(),
                p.getKtMeetingLink(),
                p.getKtNotes(),
                markedByName,
                // Live KT session (Zoom) — null when not yet scheduled.
                // zoomStartUrl is host-only; the trainer-facing detail
                // page sees it, intern-side surfaces must strip it
                // before serialisation.
                p.getKtZoomMeetingId(),
                p.getKtZoomJoinUrl(),
                p.getKtZoomStartUrl(),
                p.getKtScheduledFor(),
                p.getKtDurationMinutes(),
                p.getKtTimezone());
    }

    private CatalogProjectResponse toResponseWithCount(Project p, User creator) {
        long count = projectAssignmentRepository.countByProjectId(p.getId());
        CatalogProjectResponse.RepositoryRef repoRef = repositoryLinkRepository
                .findByProjectId(p.getId())
                .map(link -> {
                    User linkedBy = resolveCreator(link.getLinkedById());
                    CatalogProjectResponse.UserRef linkedByRef = linkedBy == null
                            ? null
                            : new CatalogProjectResponse.UserRef(
                                    linkedBy.getId(), linkedBy.getFullName());
                    return new CatalogProjectResponse.RepositoryRef(
                            link.getId(),
                            link.getRepositoryName(),
                            link.getRepositoryUrl(),
                            linkedByRef,
                            link.getCreatedAt());
                })
                .orElse(null);
        CatalogProjectResponse base = toResponse(p, creator);
        return new CatalogProjectResponse(
                base.id(),
                base.name(),
                base.description(),
                base.requirements(),
                base.objectives(),
                base.techStack(),
                base.expectedDurationDays(),
                base.deliverables(),
                base.difficulty(),
                base.instructions(),
                base.startDate(),
                base.endDate(),
                base.createdBy(),
                count,
                repoRef,
                base.kt(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private User resolveCreator(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }
}
