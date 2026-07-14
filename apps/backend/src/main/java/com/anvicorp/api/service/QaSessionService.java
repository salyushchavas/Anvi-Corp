package com.anvicorp.api.service;

import com.anvicorp.api.dto.qa.QaSessionResponse;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.QaSession;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.QaSessionStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.QaSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Q&amp;A (viva) session surface. The session row owns the scheduling /
 * conducting metadata; the project's lifecycle transitions are owned by
 * {@link ProjectWorkflowService} — this service DELEGATES on schedule, sign-off
 * and return so there's a single source of truth for status changes.
 *
 * <h2>Scoping</h2>
 * Read + write actions on a session require either the engagement's
 * {@code reportingManager} or {@code SUPER_ADMIN}. The intern (the project's
 * candidate user) can read their own session. The technical supervisor on
 * the same engagement can also read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QaSessionService {

    private final QaSessionRepository qaSessionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectWorkflowService projectWorkflowService;
    private final MeetingProvider meetingProvider;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.notification.UserNotificationDispatcher userNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    @Transactional
    public QaSession schedule(UUID projectId, Instant scheduledAt, String meetingLink,
                              Integer durationMinutes, String timezone,
                              String topic, String agenda, User caller) {
        if (scheduledAt == null) {
            throw new BadRequestException("scheduledAt is required.");
        }
        Project project = projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        ensureRmOrSuperAdmin(project, caller);
        ProjectStatus current = project.getStatus();
        if (current != ProjectStatus.TECH_APPROVED && current != ProjectStatus.PENDING_VIVA) {
            throw new BadRequestException(
                    "Q&A can only be scheduled on a TECH_APPROVED or PENDING_VIVA project (current: "
                            + current + ")");
        }

        int duration = durationMinutes == null ? 30
                : Math.max(15, Math.min(180, durationMinutes));
        String tz = (timezone == null || timezone.isBlank()) ? "UTC" : timezone;
        String meetingTopic = (topic != null && !topic.isBlank())
                ? topic
                : "Q&A — " + (project.getTitle() != null ? project.getTitle() : "Project viva");

        // Auto-create a Zoom meeting on schedule so the evaluator gets a
        // host start_url (refetched fresh via /host-start) and the intern
        // gets a join_url — same pattern as KT / weekly / doubt sessions.
        // Best-effort: a Zoom outage falls back to the manually-pasted
        // meetingLink so the schedule still goes through.
        String zoomId = null, joinUrl = null, startUrl = null, password = null;
        if (meetingProvider.isReady()) {
            try {
                String hostId = caller.getZoomEmail() != null
                        && !caller.getZoomEmail().isBlank()
                        ? caller.getZoomEmail() : "me";
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(hostId, meetingTopic, scheduledAt,
                                duration, tz, agenda));
                zoomId = z.providerMeetingId();
                joinUrl = z.joinUrl();
                startUrl = z.startUrl();
                password = z.password();
                log.info("[QaSession] {} meeting created id={} for project={}",
                        meetingProvider.providerName(), zoomId, project.getId());
            } catch (Exception e) {
                log.warn("[QaSession] {} meeting create failed (non-fatal): {}",
                        meetingProvider.providerName(), e.getMessage());
            }
        }

        QaSession session = QaSession.builder()
                .project(project)
                .scheduledAt(scheduledAt)
                .meetingLink(trimToNull(meetingLink))
                .zoomMeetingId(zoomId)
                .zoomJoinUrl(joinUrl)
                .zoomStartUrl(startUrl)
                .zoomPassword(password)
                .sessionDurationMinutes(duration)
                .sessionTimezone(tz)
                .status(QaSessionStatus.SCHEDULED)
                .scheduledBy(caller)
                .build();
        session = qaSessionRepository.save(session);

        // Flip the project to PENDING_VIVA via the workflow service (idempotent
        // when already pending). The workflow service owns the audit + event.
        if (current == ProjectStatus.TECH_APPROVED) {
            projectWorkflowService.markPendingViva(project.getId(), caller, scheduledAt);
        }
        // Intern notification — Model A: name the evaluator who scheduled.
        // Internal mail (BridgingEmailProvider routes to the company mailbox
        // for ACTIVE interns; falls through to personal SMTP otherwise) +
        // an in-app PROJECT_QA_SCHEDULED cue with the deep-link. Best-effort:
        // a delivery failure logs at WARN and never breaks the schedule.
        try {
            notifyInternOfScheduledQa(project, session, caller, joinUrl);
        } catch (Exception e) {
            log.warn("[QaSession] intern notify failed (non-fatal) for project={}: {}",
                    project.getId(), e.getMessage());
        }
        return qaSessionRepository.findByIdWithGraph(session.getId())
                .orElseThrow(() -> new IllegalStateException("Just-saved session vanished"));
    }

    /**
     * Compose + dispatch the Q&A-scheduled notification to the intern. Model
     * A naming: the body opens with "{Evaluator name}, your Evaluator,
     * scheduled your Q&A session...". The join link is always included in
     * the mail; the in-app cue's actionUrl deep-links to the project so
     * the intern lands on the project detail's Q&A card.
     */
    private void notifyInternOfScheduledQa(Project project, QaSession session,
                                           User scheduler, String joinUrl) {
        if (project == null || session == null) return;
        var intern = project.getIntern();
        User internUser = intern != null ? intern.getUser() : null;
        if (internUser == null || internUser.getId() == null) return;

        String projectTitle = project.getTitle() != null ? project.getTitle() : "your project";
        String schedulerName = scheduler != null && scheduler.getFullName() != null
                && !scheduler.getFullName().isBlank()
                ? scheduler.getFullName() : null;
        String actorPhrase = schedulerName != null
                ? schedulerName + ", your Evaluator,"
                : "Your Evaluator";
        String whenLine = session.getScheduledAt() != null
                ? "Scheduled for " + session.getScheduledAt()
                + (session.getSessionTimezone() != null
                    ? " (" + session.getSessionTimezone() + ")" : "")
                + (session.getSessionDurationMinutes() != null
                    ? " — " + session.getSessionDurationMinutes() + " min" : "")
                : "";
        // Prefer the Zoom join URL; fall back to the manually-pasted
        // meeting link so the intern always has something to click.
        String join = joinUrl != null && !joinUrl.isBlank()
                ? joinUrl : session.getMeetingLink();
        String joinLine = join != null && !join.isBlank()
                ? "\n\nJoin the session: " + join : "";
        String subject = "Q&A session scheduled — " + projectTitle;
        String body = "Hi,\n\n"
                + actorPhrase
                + " scheduled your Q&A (viva) session for project \""
                + projectTitle + "\". This is the final step before completion."
                + (whenLine.isBlank() ? "" : "\n\n" + whenLine)
                + joinLine
                + "\n\nOpen the project: /careers/intern/projects/" + project.getId()
                + "\n\n" + brand.signoff();
        try {
            internNotifications.notifyIntern(internUser.getId(), subject, body, null);
        } catch (Exception e) {
            log.debug("[QaSession] intern mail send failed (non-fatal): {}", e.getMessage());
        }
        // In-app cue — eventType PROJECT_QA_SCHEDULED, deep-link to the
        // intern's project page so they see the Q&A card on landing.
        try {
            String inAppTitle = "Q&A scheduled · " + projectTitle;
            String inAppBody = (schedulerName != null
                    ? schedulerName + " scheduled your Q&A session."
                    : "Your Evaluator scheduled your Q&A session.")
                    + (whenLine.isBlank() ? "" : " " + whenLine + ".");
            userNotifications.dispatch(internUser.getId(),
                    "PROJECT_QA_SCHEDULED",
                    internUser.getId(),
                    inAppTitle,
                    inAppBody,
                    "/careers/intern/projects/" + project.getId(),
                    true);
        } catch (Exception e) {
            log.debug("[QaSession] intern in-app cue failed (non-fatal): {}", e.getMessage());
        }
    }

    @Transactional
    public QaSession updateConducted(UUID sessionId, String questionsAsked,
                                     String internResponses, User caller) {
        QaSession session = load(sessionId);
        ensureRmOrSuperAdmin(session.getProject(), caller);
        if (session.getStatus() != QaSessionStatus.SCHEDULED
                && session.getStatus() != QaSessionStatus.CONDUCTED) {
            throw new BadRequestException(
                    "Conducted notes can only be captured on a SCHEDULED or CONDUCTED session (current: "
                            + session.getStatus() + ")");
        }
        session.setQuestionsAsked(trimToNull(questionsAsked));
        session.setInternResponses(trimToNull(internResponses));
        if (session.getStatus() == QaSessionStatus.SCHEDULED
                && (session.getQuestionsAsked() != null || session.getInternResponses() != null)) {
            session.setStatus(QaSessionStatus.CONDUCTED);
            session.setConductedBy(caller);
        }
        qaSessionRepository.save(session);
        return session;
    }

    @Transactional
    public QaSession signOff(UUID sessionId, Integer marks, String remarks, User caller) {
        QaSession session = load(sessionId);
        ensureRmOrSuperAdmin(session.getProject(), caller);
        if (session.getStatus() == QaSessionStatus.COMPLETED) return session;
        if (session.getStatus() == QaSessionStatus.RETURNED) {
            throw new BadRequestException("Session has already been returned — cannot sign off.");
        }
        if (isBlank(session.getQuestionsAsked()) || isBlank(session.getInternResponses())) {
            throw new BadRequestException(
                    "Capture questions + intern responses before signing off.");
        }
        if (marks != null && (marks < 0 || marks > 10)) {
            throw new BadRequestException("marks must be between 0 and 10.");
        }

        // Workflow service owns the project status flip + audit + event.
        projectWorkflowService.completeAfterViva(session.getProject().getId(), caller);

        session.setMarks(marks);
        session.setRemarks(trimToNull(remarks));
        session.setStatus(QaSessionStatus.COMPLETED);
        session.setConductedBy(caller);
        session.setCompletedAt(Instant.now());
        qaSessionRepository.save(session);
        return session;
    }

    @Transactional
    public QaSession returnForRevisions(UUID sessionId, String reason, User caller) {
        QaSession session = load(sessionId);
        ensureRmOrSuperAdmin(session.getProject(), caller);
        if (session.getStatus() == QaSessionStatus.COMPLETED) {
            throw new BadRequestException("Session is already signed off — cannot return.");
        }
        if (session.getStatus() == QaSessionStatus.RETURNED) return session;
        if (reason == null || reason.trim().length() < 10) {
            throw new BadRequestException("A reason of at least 10 characters is required.");
        }

        projectWorkflowService.returnForRevisions(session.getProject().getId(), caller, reason);

        session.setStatus(QaSessionStatus.RETURNED);
        session.setConductedBy(caller);
        session.setReturnReason(reason.trim());
        session.setReturnedAt(Instant.now());
        qaSessionRepository.save(session);
        return session;
    }

    @Transactional(readOnly = true)
    public QaSession get(UUID sessionId, User caller) {
        QaSession session = load(sessionId);
        ensureCanRead(session.getProject(), caller);
        return session;
    }

    @Transactional(readOnly = true)
    public List<QaSession> listByProject(UUID projectId, User caller) {
        Project project = projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
        ensureCanRead(project, caller);
        return qaSessionRepository.findByProjectIdWithGraph(projectId);
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    public QaSessionResponse toResponse(QaSession s) {
        Project p = s.getProject();
        var intern = p.getIntern();
        var internUser = intern != null ? intern.getUser() : null;
        return new QaSessionResponse(
                s.getId(),
                p.getId(),
                p.getTitle(),
                internUser != null ? internUser.getId() : null,
                internUser != null ? internUser.getFullName() : null,
                s.getScheduledAt(),
                s.getMeetingLink(),
                s.getZoomMeetingId(),
                s.getZoomJoinUrl(),
                s.getZoomStartUrl(),
                s.getStatus(),
                s.getQuestionsAsked(),
                s.getInternResponses(),
                s.getMarks(),
                s.getRemarks(),
                s.getScheduledBy() != null ? s.getScheduledBy().getId() : null,
                s.getConductedBy() != null ? s.getConductedBy().getId() : null,
                s.getCompletedAt(),
                s.getReturnedAt(),
                s.getReturnReason(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private QaSession load(UUID sessionId) {
        return qaSessionRepository.findByIdWithGraph(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Q&A session not found: " + sessionId));
    }

    // Role-based gates — per-engagement RM/supervisor FKs are not the
    // boundary. EVALUATOR (org-wide Phase 8.6.4 evaluator) or
    // REPORTING_MANAGER may act on any Q&A; reads also allow
    // TRAINER and the project's own intern.
    private static void ensureRmOrSuperAdmin(Project project, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(caller)) return;
        if (caller.getRoles() != null
                && (caller.getRoles().contains(UserRole.EVALUATOR)
                    || caller.getRoles().contains(UserRole.REPORTING_MANAGER))) {
            return;
        }
        throw new ForbiddenException(
                "Only EVALUATOR, REPORTING_MANAGER, or SUPER_ADMIN may act on this Q&A.");
    }

    private static void ensureCanRead(Project project, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(caller)) return;
        var intern = project.getIntern();
        User internUser = intern != null ? intern.getUser() : null;
        boolean isIntern = internUser != null && internUser.getId().equals(caller.getId());
        boolean hasReviewerRole = caller.getRoles() != null
                && (caller.getRoles().contains(UserRole.EVALUATOR)
                    || caller.getRoles().contains(UserRole.REPORTING_MANAGER)
                    || caller.getRoles().contains(UserRole.TRAINER));
        if (!isIntern && !hasReviewerRole) {
            throw new ForbiddenException("Not authorised to view this Q&A session.");
        }
    }

    private static boolean isSuperAdmin(User u) {
        return u.getRoles() != null && u.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
