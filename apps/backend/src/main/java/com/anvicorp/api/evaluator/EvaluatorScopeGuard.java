package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single source of truth for "may this caller act as Evaluator on this
 * intern's lifecycle". Direct mirror of
 * {@link com.anvicorp.api.trainer.TrainerScopeGuard} — same SUPER_ADMIN
 * bypass + role gate + null-FK fallback. Use it from EVERY per-intern
 * Evaluator action (lifecycle-level AND row-level evaluation gates) so
 * the surfaces never drift into the strict {@code evaluator_id == caller}
 * pattern that produced the trainer-assign 403 on null-link interns.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>{@code SUPER_ADMIN} always passes.</li>
 *   <li>Caller must hold the {@code EVALUATOR} role.</li>
 *   <li>If {@code lifecycle.evaluator_id} is null (single-evaluator org
 *       where the link wasn't stamped at offer-sign time), any EVALUATOR
 *       is the de-facto owner — mirrors the
 *       {@link com.anvicorp.api.intern.ReportingStructureAutoLinker}
 *       fill-nulls semantics and matches what the Phase A Evaluator
 *       roster already does.</li>
 *   <li>Else {@code lifecycle.evaluator_id} must equal {@code caller.id}.</li>
 * </ul>
 *
 * <p>Row-level checks (evaluation rows previously stamped with an
 * {@code evaluator_id}) should also delegate here by loading the
 * lifecycle for the row and calling this method — so the row-level gate
 * inherits the same single-evaluator fallback instead of locking the
 * current org evaluator out of a row created under a prior default
 * account.</p>
 */
@Component
@Slf4j
public class EvaluatorScopeGuard {

    /**
     * Throws {@link ForbiddenException} when {@code caller} cannot act
     * as Evaluator on {@code lc}. Returns silently when allowed. Never
     * modifies state.
     */
    public void requireEvaluatorOwnership(InternLifecycle lc, User caller) {
        if (caller == null) {
            throw new ForbiddenException("Authentication required");
        }
        if (caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            return;
        }
        if (caller.getRoles() == null
                || !caller.getRoles().contains(UserRole.EVALUATOR)) {
            throw new ForbiddenException("EVALUATOR role required");
        }
        if (lc == null || lc.getEvaluatorId() == null) {
            // Null evaluator_id is the single-evaluator-org default — any
            // EVALUATOR is the de-facto owner. Matches the Phase A
            // Evaluator roster + the ReportingStructureAutoLinker
            // fill-nulls behavior.
            log.debug("[EvaluatorScopeGuard] null evaluator_id on lifecycle={} — "
                            + "allowing EVALUATOR caller {} as de-facto owner",
                    lc != null ? lc.getId() : null,
                    caller.getId());
            return;
        }
        if (!caller.getId().equals(lc.getEvaluatorId())) {
            throw new ForbiddenException(
                    "Intern is not in your roster (assigned to a different Evaluator).");
        }
    }

    /**
     * IDOR-safe variant that hides existence on unowned access. Throws
     * {@link ResourceNotFoundException} instead of {@link ForbiddenException}
     * so the client sees the same 404 they'd see for a genuinely bogus id
     * — cross-Evaluator probing can't confirm whether a target row exists.
     * SUPER_ADMIN bypass is preserved. Callers should log the WARN before
     * invoking so operator visibility into probing is retained.
     *
     * @param resourceId caller-supplied id used only for structured logging
     *                   context on the failure path.
     */
    public void requireEvaluatorOwnershipOr404(InternLifecycle lc, User caller, UUID resourceId) {
        try {
            requireEvaluatorOwnership(lc, caller);
        } catch (ForbiddenException fe) {
            UUID callerId = caller != null ? caller.getId() : null;
            UUID lcId = lc != null ? lc.getId() : null;
            log.warn("[IDOR-guard] evaluator ownership caller={} resource={} lifecycle={} reason={}",
                    callerId, resourceId, lcId, fe.getMessage());
            throw new ResourceNotFoundException("Not found");
        }
    }
}
