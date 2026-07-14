package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.application.ProjectLifecycle;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.event.project.ProjectCompletedEvent;
import com.anvicorp.api.event.project.ProjectMarkedPendingVivaEvent;
import com.anvicorp.api.event.project.ProjectReturnedForRevisionsEvent;
import com.anvicorp.api.event.project.ProjectTechApprovedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Two-role workflow surface for {@link Project}. Holds the four new
 * transitions introduced by the two-reviewer build:
 *
 * <ul>
 *   <li>{@code techApprove} — Tech supervisor signs off post-submission
 *       ({@code SUBMITTED → TECH_APPROVED}).</li>
 *   <li>{@code returnForRevisions} — Either reviewer sends back
 *       ({@code SUBMITTED | TECH_APPROVED | PENDING_VIVA → IN_PROGRESS}).</li>
 *   <li>{@code markPendingViva} — Reporting Manager schedules the viva
 *       ({@code TECH_APPROVED → PENDING_VIVA}).</li>
 *   <li>{@code completeAfterViva} — Reporting Manager signs final completion
 *       ({@code PENDING_VIVA → COMPLETED}).</li>
 * </ul>
 *
 * <p>Existing legacy methods on {@link ProjectService}
 * ({@code returnForChanges}, {@code complete}) stay intact — they cover the
 * single-reviewer path for projects that don't go through the GitHub /
 * codespaces flow. Both paths now publish
 * {@link ProjectCompletedEvent} on terminal close, so downstream listeners
 * (offboarding, badges) are reviewer-agnostic.</p>
 *
 * <h2>Side effects</h2>
 * Side effects (email, audit) live in {@code @EventListener} beans, NOT in
 * this service. The transitions here publish domain events through
 * {@link ApplicationEventPublisher}.
 *
 * <h2>Authorisation</h2>
 * <ul>
 *   <li>{@code techApprove} + first-step {@code returnForRevisions} from
 *       {@code SUBMITTED}: engagement supervisor (technical) or
 *       {@code SUPER_ADMIN}.</li>
 *   <li>{@code markPendingViva}, {@code completeAfterViva}, and returns from
 *       {@code TECH_APPROVED}/{@code PENDING_VIVA}: engagement
 *       {@code reportingManager} or {@code SUPER_ADMIN}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectWorkflowService {

    private final ProjectRepository projectRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Project techApprove(UUID projectId, User actor) {
        Project project = load(projectId);
        ensureTechnicalReviewer(project, actor);
        ProjectStatus from = project.getStatus();
        if (from == ProjectStatus.TECH_APPROVED) return project; // idempotent
        ensureLegal(from, ProjectStatus.TECH_APPROVED);
        if (from != ProjectStatus.SUBMITTED) {
            // Lifecycle map only allows SUBMITTED → TECH_APPROVED; defence
            // in depth so a future map widening doesn't unintentionally
            // shortcut RETURNED or NOT_STARTED into tech-approval.
            throw new BadRequestException(
                    "Tech approval requires a SUBMITTED project (current: " + from + ")");
        }

        Project saved = apply(project, ProjectStatus.TECH_APPROVED, "PROJECT_TECH_APPROVED",
                actor, null);
        eventPublisher.publishEvent(new ProjectTechApprovedEvent(saved.getId(),
                actor != null ? actor.getId() : null));
        return saved;
    }

    /**
     * Reviewer (tech supervisor or RM, depending on current state) sends the
     * project back to the intern. Drops to {@code IN_PROGRESS} so the intern
     * can iterate and resubmit.
     */
    @Transactional
    public Project returnForRevisions(UUID projectId, User actor, String reason) {
        Project project = load(projectId);
        ProjectStatus from = project.getStatus();
        if (from == ProjectStatus.SUBMITTED) {
            ensureTechnicalReviewer(project, actor);
        } else if (from == ProjectStatus.TECH_APPROVED || from == ProjectStatus.PENDING_VIVA) {
            ensureReportingManagerOrSuperAdmin(project, actor);
        } else {
            throw new BadRequestException(
                    "Can't return a project in status " + from);
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required when returning for revisions.");
        }
        ensureLegal(from, ProjectStatus.IN_PROGRESS);

        Project saved = apply(project, ProjectStatus.IN_PROGRESS,
                "PROJECT_RETURNED_FOR_REVISIONS", actor,
                Map.of("from", from.name(), "reason", reason.trim()));
        // Persist the reviewer notes so the intern sees them on the project
        // page — mirrors the legacy returnForChanges path.
        saved.setReviewNotes(reason.trim());
        saved.setReviewedBy(actor);
        saved.setReviewedAt(Instant.now());
        saved = projectRepository.save(saved);

        eventPublisher.publishEvent(new ProjectReturnedForRevisionsEvent(
                saved.getId(), actor != null ? actor.getId() : null, reason.trim()));
        return saved;
    }

    @Transactional
    public Project markPendingViva(UUID projectId, User actor, Instant scheduledAt) {
        Project project = load(projectId);
        ensureReportingManagerOrSuperAdmin(project, actor);
        ProjectStatus from = project.getStatus();
        if (from == ProjectStatus.PENDING_VIVA) return project; // idempotent
        ensureLegal(from, ProjectStatus.PENDING_VIVA);
        if (from != ProjectStatus.TECH_APPROVED) {
            throw new BadRequestException(
                    "Mark-pending-viva requires TECH_APPROVED (current: " + from + ")");
        }

        Project saved = apply(project, ProjectStatus.PENDING_VIVA, "PROJECT_PENDING_VIVA",
                actor, scheduledAt != null
                        ? Map.of("scheduledAt", scheduledAt.toString()) : null);
        eventPublisher.publishEvent(new ProjectMarkedPendingVivaEvent(
                saved.getId(), actor != null ? actor.getId() : null, scheduledAt));
        return saved;
    }

    @Transactional
    public Project completeAfterViva(UUID projectId, User actor) {
        Project project = load(projectId);
        ensureReportingManagerOrSuperAdmin(project, actor);
        ProjectStatus from = project.getStatus();
        if (from == ProjectStatus.COMPLETED) return project; // idempotent
        ensureLegal(from, ProjectStatus.COMPLETED);
        if (from != ProjectStatus.PENDING_VIVA && from != ProjectStatus.TECH_APPROVED) {
            throw new BadRequestException(
                    "Complete-after-viva requires PENDING_VIVA or TECH_APPROVED (current: "
                            + from + ")");
        }

        Project saved = apply(project, ProjectStatus.COMPLETED, "PROJECT_COMPLETED",
                actor, null);
        // Mirror the legacy complete()'s side stamps so existing readers stay
        // happy. Lifecycle doesn't care about these — they're convenience.
        Instant now = Instant.now();
        saved.setReviewedBy(actor);
        saved.setReviewedAt(now);
        saved.setCompletedAt(now);
        saved.setProgressPct(100);
        saved = projectRepository.save(saved);

        eventPublisher.publishEvent(new ProjectCompletedEvent(saved.getId(),
                actor != null ? actor.getId() : null));
        return saved;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Project apply(Project project, ProjectStatus target, String auditAction,
                          User actor, Map<String, Object> afterExtras) {
        ProjectStatus from = project.getStatus();
        project.setStatus(target);
        Project saved = projectRepository.save(project);
        writeAudit(saved.getId(), auditAction, actor != null ? actor.getId() : null,
                from, target, afterExtras);
        log.info("project.transition project={} from={} to={} actor={}",
                saved.getId(), from, target, actor != null ? actor.getId() : null);
        return saved;
    }

    private Project load(UUID projectId) {
        return projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
    }

    private static void ensureLegal(ProjectStatus from, ProjectStatus to) {
        if (!ProjectLifecycle.isLegal(from, to)) {
            throw new ConflictException(
                    "Illegal project transition " + from + " → " + to);
        }
    }

    // Role-based gates — per-engagement FKs are no longer permission
    // boundaries. Any TECHNICAL_EVALUATOR / REPORTING_MANAGER (or
    // SUPER_ADMIN) may act on any project at the appropriate stage.
    private static void ensureTechnicalReviewer(Project project, User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(actor)) return;
        if (actor.getRoles() != null
                && actor.getRoles().contains(UserRole.TRAINER)) {
            return;
        }
        throw new ForbiddenException(
                "Only TECHNICAL_EVALUATOR or SUPER_ADMIN may act here.");
    }

    private static void ensureReportingManagerOrSuperAdmin(Project project, User actor) {
        if (actor == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(actor)) return;
        if (actor.getRoles() != null
                && (actor.getRoles().contains(UserRole.EVALUATOR)
                    || actor.getRoles().contains(UserRole.REPORTING_MANAGER))) {
            return;
        }
        throw new ForbiddenException(
                "Only EVALUATOR, REPORTING_MANAGER, or SUPER_ADMIN may act here.");
    }

    private static boolean isSuperAdmin(User u) {
        return u.getRoles() != null && u.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    private void writeAudit(UUID projectId, String action, UUID userId,
                            ProjectStatus from, ProjectStatus to,
                            Map<String, Object> afterExtras) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", from);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", to);
        if (afterExtras != null) after.putAll(afterExtras);
        AuditLog row = AuditLog.builder()
                .entityType("Project")
                .entityId(projectId)
                .action(action)
                .userId(userId)
                .beforeJson(serialize(before))
                .afterJson(serialize(after))
                .build();
        try {
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("Failed to write {} audit (non-fatal) for project {}: {}",
                    action, projectId, e.getMessage());
        }
    }

    private String serialize(Map<String, Object> snap) {
        try {
            return objectMapper.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return String.valueOf(snap);
        }
    }
}
