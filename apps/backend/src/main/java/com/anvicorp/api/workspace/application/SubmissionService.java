package com.anvicorp.api.workspace.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.event.project.ProjectSubmittedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.service.ProjectWorkflowService;
import com.anvicorp.api.workspace.domain.ProjectWorkspaceFile;
import com.anvicorp.api.workspace.domain.ReviewOutcome;
import com.anvicorp.api.workspace.domain.WorkspaceSubmission;
import com.anvicorp.api.workspace.domain.WorkspaceSubmittedFile;
import com.anvicorp.api.workspace.infrastructure.ProjectWorkspaceFileRepository;
import com.anvicorp.api.workspace.infrastructure.WorkspaceSubmissionRepository;
import com.anvicorp.api.workspace.infrastructure.WorkspaceSubmittedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable submission workflow on top of {@link ProjectWorkflowService}.
 *
 * <p>This service DOES NOT re-implement project-status transitions. It
 * <i>wraps</i> the existing workflow service: the {@code approveTechnically}
 * and {@code returnForRevisions} calls delegate to
 * {@link ProjectWorkflowService} for the status change + audit + event,
 * then stamp the review fields on the {@link WorkspaceSubmission} row.</p>
 *
 * <h2>Submit</h2>
 * <ol>
 *   <li>Guard: caller is the intern, project is {@code IN_PROGRESS}, at
 *       least one workspace file.</li>
 *   <li>Compute next submission number.</li>
 *   <li>Insert {@link WorkspaceSubmission} (PENDING).</li>
 *   <li>Insert one {@link WorkspaceSubmittedFile} per current workspace
 *       file (deep copy of content).</li>
 *   <li>Transition project to {@code SUBMITTED} via direct save (no
 *       workflow service for SUBMITTED — that's an intern action, not a
 *       reviewer action). Publish {@link ProjectSubmittedEvent} so the
 *       evaluator email + audit row fire from the existing listener.</li>
 * </ol>
 *
 * <h2>Approve / Return</h2>
 * Reviewer actions go through {@link ProjectWorkflowService}; this service
 * supplements with the per-submission review-outcome stamp.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {

    private static final int MAX_REASON_LENGTH = 2000;
    private static final int MIN_REASON_LENGTH = 10;

    private final ProjectRepository projectRepository;
    private final ProjectWorkflowService projectWorkflowService;
    private final ProjectWorkspaceFileRepository workspaceFileRepository;
    private final WorkspaceSubmissionRepository submissionRepository;
    private final WorkspaceSubmittedFileRepository submittedFileRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ── Submit ──────────────────────────────────────────────────────────────

    @Transactional
    public WorkspaceSubmission submit(UUID projectId, User caller) {
        Project project = loadProject(projectId);
        ensureIntern(project, caller);
        if (project.getStatus() != ProjectStatus.IN_PROGRESS) {
            throw new ConflictException(
                    "Project must be IN_PROGRESS to submit (current: "
                            + project.getStatus() + ").");
        }
        List<ProjectWorkspaceFile> files =
                workspaceFileRepository.findByProjectIdOrderByPathAsc(projectId);
        if (files.isEmpty()) {
            throw new BadRequestException(
                    "Add at least one file to the workspace before submitting.");
        }

        // 1. Submission row.
        int next = submissionRepository.maxSubmissionNumber(projectId) + 1;
        WorkspaceSubmission submission = WorkspaceSubmission.builder()
                .projectId(projectId)
                .submissionNumber(next)
                .submittedBy(caller.getId())
                .reviewOutcome(ReviewOutcome.PENDING)
                .build();
        submission = submissionRepository.save(submission);

        // 2. Frozen file copies.
        List<WorkspaceSubmittedFile> frozen = new ArrayList<>(files.size());
        for (ProjectWorkspaceFile f : files) {
            frozen.add(WorkspaceSubmittedFile.builder()
                    .submissionId(submission.getId())
                    .path(f.getPath())
                    .content(f.getContent())
                    .sizeBytes(f.getSizeBytes())
                    .build());
        }
        submittedFileRepository.saveAll(frozen);

        // 3. Project status flip — intern-driven, so we save directly here
        //    rather than route through ProjectWorkflowService (which is for
        //    reviewer-driven transitions). Same legal-transition shape;
        //    ProjectLifecycle.LEGAL_TRANSITIONS allows IN_PROGRESS→SUBMITTED.
        project.setStatus(ProjectStatus.SUBMITTED);
        projectRepository.save(project);

        // 4. Audit + event.
        writeAudit(projectId, "WORKSPACE_SUBMITTED", caller.getId(), Map.of(
                "submissionId", submission.getId().toString(),
                "submissionNumber", next,
                "fileCount", files.size()));
        eventPublisher.publishEvent(new ProjectSubmittedEvent(
                projectId, submission.getId(), caller.getId()));

        log.info("submission.transition project={} submission={} from=IN_PROGRESS to=SUBMITTED actor={}",
                projectId, next, caller.getId());
        return submission;
    }

    // ── Read ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public WorkspaceSubmission getSubmission(UUID submissionId, User caller) {
        WorkspaceSubmission submission = loadSubmission(submissionId);
        Project project = loadProject(submission.getProjectId());
        ensureCanRead(project, caller);
        return submission;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSubmittedFile> getSubmissionFiles(UUID submissionId, User caller) {
        WorkspaceSubmission submission = loadSubmission(submissionId);
        Project project = loadProject(submission.getProjectId());
        ensureCanRead(project, caller);
        return submittedFileRepository.findBySubmissionIdOrderByPathAsc(submissionId);
    }

    @Transactional(readOnly = true)
    public WorkspaceSubmittedFile getSubmissionFile(UUID submissionId, String path, User caller) {
        WorkspaceSubmission submission = loadSubmission(submissionId);
        Project project = loadProject(submission.getProjectId());
        ensureCanRead(project, caller);
        WorkspaceFileService.validatePath(path);
        return submittedFileRepository.findBySubmissionIdAndPath(submissionId, path)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "File not found in this submission: " + path));
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSubmission> listForProject(UUID projectId, User caller) {
        Project project = loadProject(projectId);
        ensureCanRead(project, caller);
        return submissionRepository.findByProjectIdOrderBySubmittedAtDesc(projectId);
    }

    // ── Review ─────────────────────────────────────────────────────────────

    /**
     * Tech evaluator approves the submission. Wraps
     * {@link ProjectWorkflowService#techApprove(UUID, User)} which handles
     * the {@code SUBMITTED → TECH_APPROVED} transition, audit row, and
     * {@code ProjectTechApprovedEvent} publish. We then stamp the review
     * outcome on the submission row.
     */
    @Transactional
    public WorkspaceSubmission approveTechnically(UUID submissionId, User evaluator) {
        WorkspaceSubmission submission = loadSubmission(submissionId);
        ensurePendingForReview(submission);

        // Delegate the status transition + audit + event to the existing service.
        projectWorkflowService.techApprove(submission.getProjectId(), evaluator);

        submission.setReviewOutcome(ReviewOutcome.APPROVED);
        submission.setReviewedAt(Instant.now());
        submission.setReviewerId(evaluator != null ? evaluator.getId() : null);
        WorkspaceSubmission saved = submissionRepository.save(submission);

        writeAudit(submission.getProjectId(), "WORKSPACE_SUBMISSION_APPROVED",
                evaluator != null ? evaluator.getId() : null, Map.of(
                        "submissionId", submission.getId().toString(),
                        "submissionNumber", submission.getSubmissionNumber()));
        log.info("submission.transition project={} submission={} outcome=APPROVED actor={}",
                submission.getProjectId(), submission.getSubmissionNumber(),
                evaluator != null ? evaluator.getId() : null);
        return saved;
    }

    /**
     * Tech evaluator returns the submission. Wraps
     * {@link ProjectWorkflowService#returnForRevisions(UUID, User, String)}.
     */
    @Transactional
    public WorkspaceSubmission returnForRevisions(UUID submissionId, User evaluator, String reason) {
        WorkspaceSubmission submission = loadSubmission(submissionId);
        ensurePendingForReview(submission);
        String trimmed = reason != null ? reason.trim() : "";
        if (trimmed.length() < MIN_REASON_LENGTH) {
            throw new BadRequestException(
                    "Reason must be at least " + MIN_REASON_LENGTH + " characters.");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new BadRequestException(
                    "Reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }

        projectWorkflowService.returnForRevisions(submission.getProjectId(), evaluator, trimmed);

        submission.setReviewOutcome(ReviewOutcome.RETURNED);
        submission.setReviewedAt(Instant.now());
        submission.setReviewerId(evaluator != null ? evaluator.getId() : null);
        submission.setReviewReason(trimmed);
        WorkspaceSubmission saved = submissionRepository.save(submission);

        writeAudit(submission.getProjectId(), "WORKSPACE_SUBMISSION_RETURNED",
                evaluator != null ? evaluator.getId() : null, Map.of(
                        "submissionId", submission.getId().toString(),
                        "submissionNumber", submission.getSubmissionNumber()));
        log.info("submission.transition project={} submission={} outcome=RETURNED actor={}",
                submission.getProjectId(), submission.getSubmissionNumber(),
                evaluator != null ? evaluator.getId() : null);
        return saved;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Project loadProject(UUID projectId) {
        return projectRepository.findByIdWithGraph(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found: " + projectId));
    }

    private WorkspaceSubmission loadSubmission(UUID submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Submission not found: " + submissionId));
    }

    private static void ensureIntern(Project project, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        if (u == null || !u.getId().equals(caller.getId())) {
            throw new ForbiddenException(
                    "Only this project's intern may submit.");
        }
    }

    private static void ensureCanRead(Project project, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        // Role-based — any TECHNICAL_EVALUATOR / REPORTING_MANAGER can read
        // any submission. Per-engagement FKs are no longer the boundary.
        if (caller.getRoles() != null
                && (caller.getRoles().contains(UserRole.TRAINER)
                    || caller.getRoles().contains(UserRole.REPORTING_MANAGER))) {
            return;
        }
        Candidate intern = project.getIntern();
        User internUser = intern != null ? intern.getUser() : null;
        if (internUser != null && internUser.getId().equals(caller.getId())) return;
        throw new ForbiddenException(
                "Only this project's intern, evaluator, or SUPER_ADMIN may view this submission.");
    }

    private static void ensurePendingForReview(WorkspaceSubmission submission) {
        if (submission.getReviewOutcome() != ReviewOutcome.PENDING) {
            throw new ConflictException(
                    "This submission is already " + submission.getReviewOutcome()
                            + ". A new submission is required for a fresh review.");
        }
    }

    private void writeAudit(UUID projectId, String action, UUID userId,
                            Map<String, Object> afterExtras) {
        Map<String, Object> after = new LinkedHashMap<>();
        if (afterExtras != null) after.putAll(afterExtras);
        try {
            AuditLog row = AuditLog.builder()
                    .entityType("Project")
                    .entityId(projectId)
                    .action(action)
                    .userId(userId)
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(row);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize {} audit (non-fatal) for project {}: {}",
                    action, projectId, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to write {} audit (non-fatal) for project {}: {}",
                    action, projectId, e.getMessage());
        }
    }
}
