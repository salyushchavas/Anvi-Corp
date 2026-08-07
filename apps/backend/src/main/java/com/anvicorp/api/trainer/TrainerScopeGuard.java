package com.anvicorp.api.trainer;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single source of truth for "may this caller act as Trainer on this
 * intern's lifecycle". Replaces the divergent ownership checks that
 * existed on {@code TrainerProjectAssignmentService.requireInScope}
 * (strict {@code trainer_id == caller}, broke for null-trainer rows)
 * and {@code ProjectCatalogService.ensureTrainerForProject} (already
 * had the right fallback). One model, no third variant.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>{@code SUPER_ADMIN} always passes.</li>
 *   <li>Caller must hold the {@code TRAINER} role.</li>
 *   <li>If {@code lifecycle.trainer_id} is null (single-trainer org
 *       where the link wasn't stamped at activation time), any TRAINER
 *       is treated as the de-facto owner — mirrors the Phase A roster
 *       (no trainer_id filter) + the KT mark-done semantics.</li>
 *   <li>If {@code lifecycle.trainer_id} is set, it must equal
 *       {@code caller.id}.</li>
 * </ul>
 */
@Component
@Slf4j
public class TrainerScopeGuard {

    /**
     * Throws {@link ForbiddenException} when {@code caller} cannot act
     * as Trainer on {@code lc}. Returns silently when the call is
     * allowed. Never modifies state.
     */
    public void requireTrainerOwnership(InternLifecycle lc, User caller) {
        if (caller == null) {
            throw new ForbiddenException("Authentication required");
        }
        if (caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            return;
        }
        if (caller.getRoles() == null
                || !caller.getRoles().contains(UserRole.TRAINER)) {
            throw new ForbiddenException("TRAINER role required");
        }
        if (lc == null || lc.getTrainerId() == null) {
            // Null trainer_id is the single-trainer-org default — any
            // TRAINER is the de-facto owner. Same fallback the KT
            // mark-done flow + the Phase A roster already use.
            log.debug("[TrainerScopeGuard] null trainer_id on lifecycle={} — "
                            + "allowing TRAINER caller {} as de-facto owner",
                    lc != null ? lc.getId() : null,
                    caller.getId());
            return;
        }
        if (!caller.getId().equals(lc.getTrainerId())) {
            throw new ForbiddenException(
                    "Intern is not in your roster (assigned to a different Trainer).");
        }
    }

    /**
     * IDOR-safe variant that hides existence on unowned access. Throws
     * {@link ResourceNotFoundException} instead of {@link ForbiddenException}
     * so the client sees the same 404 they'd see for a genuinely bogus id
     * — cross-Trainer probing can't confirm whether a target row exists.
     * SUPER_ADMIN bypass is preserved.
     *
     * @param resourceId caller-supplied id used only for structured logging
     *                   context on the failure path.
     */
    public void requireTrainerOwnershipOr404(InternLifecycle lc, User caller, UUID resourceId) {
        try {
            requireTrainerOwnership(lc, caller);
        } catch (ForbiddenException fe) {
            UUID callerId = caller != null ? caller.getId() : null;
            UUID lcId = lc != null ? lc.getId() : null;
            log.warn("[IDOR-guard] trainer ownership caller={} resource={} lifecycle={} reason={}",
                    callerId, resourceId, lcId, fe.getMessage());
            throw new ResourceNotFoundException("Not found");
        }
    }
}
