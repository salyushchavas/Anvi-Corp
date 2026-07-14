package com.anvicorp.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.ProjectAssignment;
import com.anvicorp.api.entity.ProjectRepositoryLink;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.event.ExitInitiatedEvent;
import com.anvicorp.api.event.GithubAccessRevokedEvent;
import com.anvicorp.api.github.GitHubService;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.ProjectRepositoryLinkRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.ExitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 8 — listens on {@link ExitInitiatedEvent} (AFTER_COMMIT so the
 * ExitRecord + lifecycle flip are durably persisted before we touch
 * GitHub) and walks every project the intern had collaborator access on,
 * calling {@code GitHubService.removeCollaborator} for each. Outcome is
 * summarised back into the ExitRecord via {@link ExitService}; a
 * {@link GithubAccessRevokedEvent} then notifies ERM.
 *
 * <p>Best-effort end-to-end: every individual call is wrapped, failures
 * accumulate into the summary, and the listener never blocks the
 * upstream exit transition.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GithubRevocationListener {

    private static final Pattern GITHUB_REPO_URL =
            Pattern.compile("^https?://github\\.com/([\\w.-]+)/([\\w.-]+?)(\\.git)?/?$");

    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final ProjectRepositoryLinkRepository repositoryLinkRepository;
    private final UserRepository userRepository;
    private final GitHubService gitHubService;
    private final ExitService exitService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /** Boot-time visibility — operator can grep the log to confirm the listener wired up. */
    @EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void onStartup() {
        log.info("[GithubRevocation] listener registered (gitHubConfigured={})",
                gitHubService.isConfigured());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onExitInitiated(ExitInitiatedEvent event) {
        if (event == null) return;
        try {
            handle(event);
        } catch (Exception e) {
            log.warn("[GithubRevocation] handler failed (non-fatal) for record {}: {}",
                    event.getExitRecordId(), e.getMessage(), e);
        }
    }

    private void handle(ExitInitiatedEvent event) {
        Instant attemptedAt = Instant.now();
        User intern = userRepository.findById(event.getInternUserId()).orElse(null);
        String githubUsername = intern != null ? intern.getGithubUsername() : null;

        if (!gitHubService.isConfigured()) {
            String summary = "GitHub disabled, no revocations needed.";
            exitService.recordRevocationOutcome(event.getExitRecordId(), attemptedAt, summary, true);
            publishRevokedEvent(event, 0, 0, summary);
            log.info("[GithubRevocation] {} — GitHub not configured; marked done", event.getExitRecordId());
            return;
        }

        if (githubUsername == null || githubUsername.isBlank()) {
            String summary = "No GitHub username on intern user; revocation skipped.";
            exitService.recordRevocationOutcome(event.getExitRecordId(), attemptedAt, summary, true);
            publishRevokedEvent(event, 0, 0, summary);
            log.info("[GithubRevocation] {} — no github_username; marked done",
                    event.getExitRecordId());
            return;
        }

        // Walk every assignment with accessGranted=true; for each, find the
        // repo link and call removeCollaborator. One ExitRecord per intern, so
        // the intern-id keys the lookup.
        List<ProjectAssignment> assignments = projectAssignmentRepository
                .findByInternIdOrderByAssignmentDateDescCreatedAtDesc(event.getInternUserId());
        List<String> failures = new java.util.ArrayList<>();
        int attempted = 0;
        int succeeded = 0;
        for (ProjectAssignment pa : assignments) {
            if (!Boolean.TRUE.equals(pa.getAccessGranted())) continue;
            ProjectRepositoryLink link = repositoryLinkRepository
                    .findByProjectId(pa.getProjectId()).orElse(null);
            if (link == null || link.getRepositoryUrl() == null) {
                continue;
            }
            Matcher m = GITHUB_REPO_URL.matcher(link.getRepositoryUrl().trim());
            if (!m.matches()) {
                continue;
            }
            String owner = m.group(1);
            String repo = m.group(2);
            attempted++;
            boolean ok;
            try {
                ok = gitHubService.removeCollaborator(owner, repo, githubUsername);
            } catch (Exception e) {
                ok = false;
                failures.add(owner + "/" + repo + " (" + e.getClass().getSimpleName() + ")");
                log.warn("[GithubRevocation] {} -> {}/{} failed: {}",
                        githubUsername, owner, repo, e.getMessage());
            }
            if (ok) {
                succeeded++;
                writeAudit(event.getInitiatedByUserId(), event.getInternUserId(),
                        true, owner, repo, githubUsername);
            } else {
                if (failures.stream().noneMatch(s -> s.startsWith(owner + "/" + repo))) {
                    failures.add(owner + "/" + repo + " (403/unexpected)");
                }
                writeAudit(event.getInitiatedByUserId(), event.getInternUserId(),
                        false, owner, repo, githubUsername);
            }
        }

        String summary;
        if (attempted == 0) {
            summary = "No repos with access_granted to revoke.";
        } else if (failures.isEmpty()) {
            summary = "Removed from " + succeeded + "/" + attempted + " repos.";
        } else {
            summary = "Removed from " + succeeded + "/" + attempted
                    + " repos. Failures: " + String.join(", ", failures);
        }
        boolean allOk = failures.isEmpty();
        exitService.recordRevocationOutcome(event.getExitRecordId(), attemptedAt, summary, allOk);
        publishRevokedEvent(event, attempted, succeeded, summary);
        log.info("[GithubRevocation] record={} intern={} attempted={} succeeded={} failed={}",
                event.getExitRecordId(), event.getInternUserId(),
                attempted, succeeded, attempted - succeeded);
    }

    private void publishRevokedEvent(ExitInitiatedEvent src, int attempted, int succeeded,
                                      String summary) {
        try {
            eventPublisher.publishEvent(new GithubAccessRevokedEvent(
                    src.getExitRecordId(), src.getInternUserId(),
                    attempted, succeeded, summary));
        } catch (Exception e) {
            log.debug("[GithubRevocation] notify event publish failed: {}", e.getMessage());
        }
    }

    private void writeAudit(java.util.UUID actorId, java.util.UUID subjectUserId,
                             boolean success, String owner, String repo, String username) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("owner", owner);
            after.put("repo", repo);
            after.put("username", username);
            after.put("success", success);
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType("ProjectRepositoryLink")
                    .action("GITHUB_ACCESS_REVOKED")
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.debug("[GithubRevocation] audit write failed: {}", e.getMessage());
        }
    }
}
