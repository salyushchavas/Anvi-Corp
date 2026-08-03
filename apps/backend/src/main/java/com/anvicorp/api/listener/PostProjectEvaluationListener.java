package com.anvicorp.api.listener;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.event.project.ProjectCompletedEvent;
import com.anvicorp.api.intern.InternEvaluationService;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 6 — when Phase 5's ProjectCompletedEvent fires, auto-draft a
 * POST_PROJECT evaluation row for the assigned Evaluator. Best-effort:
 * a failure logs but never rolls back the project completion.
 *
 * <p>Evaluator resolution is delegated to
 * {@link InternEvaluationService#autoDraftPostProject} which routes
 * through {@code OrgTeamResolver}: the per-intern
 * {@code intern_lifecycles.evaluator_id} FK is used when set, otherwise
 * the configured {@code app.default-evaluator-email}. Only when even
 * the org fallback is unresolvable does the draft skip — and it skips
 * with a WARN (never silent), so ERM notice paths + the picker's
 * self-heal path both surface the miss.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostProjectEvaluationListener {

    private final ProjectRepository projectRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final InternEvaluationService evaluationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectCompleted(ProjectCompletedEvent event) {
        try {
            Project project = projectRepository.findById(event.getProjectId()).orElse(null);
            if (project == null) {
                log.warn("[PostProjectEval] project {} not found post-commit; skipping",
                        event.getProjectId());
                return;
            }
            // Resolve the intern user id via the project's candidate → user chain.
            java.util.UUID internUserId = project.getIntern() != null
                    && project.getIntern().getUser() != null
                    ? project.getIntern().getUser().getId() : null;
            if (internUserId == null) {
                log.warn("[PostProjectEval] project {} has no resolvable intern user; skipping",
                        event.getProjectId());
                return;
            }
            InternLifecycle lc = lifecycleRepository.findByUserId(internUserId).orElse(null);
            if (lc == null) {
                log.warn("[PostProjectEval] no InternLifecycle for user {}; skipping",
                        internUserId);
                return;
            }
            evaluationService.autoDraftPostProject(lc, project.getId(),
                    event.getClosedByUserId());
            log.info("[PostProjectEval] auto-drafted POST_PROJECT eval for project={} intern={}",
                    project.getId(), internUserId);
        } catch (Exception e) {
            log.warn("[PostProjectEval] auto-draft failed (non-fatal): {}", e.getMessage());
        }
    }
}
