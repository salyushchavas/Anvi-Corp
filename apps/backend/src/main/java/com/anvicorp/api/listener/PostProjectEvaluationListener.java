package com.anvicorp.api.listener;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.event.project.ProjectCompletedEvent;
import com.anvicorp.api.intern.InternEvaluationService;
import com.anvicorp.api.intern.OrgTeamResolver;
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
 * <p>If the lifecycle has no evaluator assigned, the draft is skipped
 * with a warning — Phase 7 will fan that into a SentNotification for ERM
 * ("Assign evaluator to {intern}").</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostProjectEvaluationListener {

    private final ProjectRepository projectRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final InternEvaluationService evaluationService;
    private final OrgTeamResolver orgTeamResolver;

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
            // Evaluator resolves through OrgTeamResolver: the org-wide
            // singleton (DEFAULT_EVALUATOR_EMAIL) is used when the
            // per-intern evaluator_id was never stamped. Only warn +
            // skip when even the resolver returns null (env var unset).
            if (orgTeamResolver.resolveEvaluatorId(lc) == null) {
                log.warn("[PostProjectEval] no evaluator resolvable for lifecycle {} "
                                + "(per-intern FK null and DEFAULT_EVALUATOR_EMAIL "
                                + "unset / unresolvable) — skipping auto-draft", lc.getId());
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
