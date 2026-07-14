package com.anvicorp.api.service;

import com.anvicorp.api.entity.ExitRecord;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.LifecycleClosedException;
import com.anvicorp.api.repository.ExitRecordRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 8 — single source of truth for "can this write land?" against an
 * intern lifecycle. Once a lifecycle has an {@link ExitRecord}, the rules
 * are:
 *
 * <ul>
 *   <li>{@link WriteIntent#CREATE_NEW} → blocked immediately.</li>
 *   <li>{@link WriteIntent#RESOLVE_EXISTING} → allowed for 30 days
 *       past the exit_date so approvers can close out pending timesheets,
 *       evaluators can amend just-published rows, etc.</li>
 *   <li>SUPER_ADMIN bypasses all checks (operational override).</li>
 * </ul>
 *
 * <p>Sweeping every Phase 2-6 write service: inject this policy and call
 * {@link #ensureCanWrite(User, UUID, WriteIntent)} at the start. Throws
 * {@link LifecycleClosedException} on a hard fail — the controller layer
 * maps to {@code 409 CONFLICT}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LifecycleAccessPolicy {

    /** Cleanup window past exit_date during which RESOLVE_EXISTING is allowed. */
    private static final int CLEANUP_WINDOW_DAYS = 30;

    private final InternLifecycleRepository internLifecycleRepository;
    private final ExitRecordRepository exitRecordRepository;
    private final UserRepository userRepository;

    public enum WriteIntent {
        /** New row about the intern's life (project, timesheet, evaluation, etc.). */
        CREATE_NEW,
        /** Closing/finalising prior state (approve, finalize, reject, complete). */
        RESOLVE_EXISTING
    }

    /**
     * Pre-write guard. {@code internUserId} may be null when the target is
     * pre-lifecycle (applicants without a lifecycle row are always
     * writable; nothing to block).
     */
    public void ensureCanWrite(User actor, UUID internUserId, WriteIntent intent) {
        if (actor != null && isSuperAdmin(actor)) return;
        if (internUserId == null) return;
        if (!canWriteFor(internUserId, intent)) {
            String detail = describeBlock(internUserId, intent);
            throw new LifecycleClosedException(detail);
        }
    }

    /** Same as {@link #ensureCanWrite} but boolean — for read-side gating. */
    public boolean canWrite(User actor, UUID internUserId, WriteIntent intent) {
        if (actor != null && isSuperAdmin(actor)) return true;
        if (internUserId == null) return true;
        return canWriteFor(internUserId, intent);
    }

    private boolean canWriteFor(UUID internUserId, WriteIntent intent) {
        Optional<InternLifecycle> lc = lookupLifecycle(internUserId);
        if (lc.isEmpty()) {
            // No lifecycle row yet → pre-OFFER_SIGNED applicant; always writable.
            return true;
        }
        InternLifecycle row = lc.get();
        String activeStatus = row.getActiveStatus();
        // ACTIVE / PROSPECTIVE → always writable.
        if (activeStatus == null || "ACTIVE".equals(activeStatus)
                || "PROSPECTIVE".equals(activeStatus)) {
            return true;
        }
        // Lifecycle closed. CREATE_NEW always blocked.
        if (intent == WriteIntent.CREATE_NEW) return false;
        // RESOLVE_EXISTING allowed inside the cleanup window.
        Optional<ExitRecord> exit = exitRecordRepository.findByInternLifecycleId(row.getId());
        if (exit.isEmpty()) {
            // Activestatus says closed but no exit record → fail closed.
            return false;
        }
        Instant cutoff = exit.get().getExitDate()
                .plusDays(CLEANUP_WINDOW_DAYS)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant();
        return Instant.now().isBefore(cutoff);
    }

    private String describeBlock(UUID internUserId, WriteIntent intent) {
        Optional<InternLifecycle> lc = lookupLifecycle(internUserId);
        String status = lc.map(InternLifecycle::getActiveStatus).orElse("UNKNOWN");
        if (intent == WriteIntent.CREATE_NEW) {
            return "Action not allowed; internship is " + status + ".";
        }
        // RESOLVE_EXISTING blocked → window closed.
        Optional<ExitRecord> exit = lc.flatMap(row ->
                exitRecordRepository.findByInternLifecycleId(row.getId()));
        if (exit.isPresent()) {
            long days = ChronoUnit.DAYS.between(exit.get().getExitDate(),
                    java.time.LocalDate.now());
            return "Cleanup window closed (" + days + " days past exit). "
                    + "Contact SUPER_ADMIN for override.";
        }
        return "Action not allowed; internship is " + status + ".";
    }

    private Optional<InternLifecycle> lookupLifecycle(UUID internUserId) {
        try {
            return internLifecycleRepository.findByUserId(internUserId);
        } catch (Exception e) {
            log.warn("[LifecyclePolicy] lifecycle lookup failed (non-fatal) for {}: {}",
                    internUserId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolve a target intern userId from a candidate user id (often
     * already the intern's userId — but services that operate on
     * Candidate rows can call this for safety).
     */
    public UUID resolveInternUserIdFromCandidate(UUID candidateUserId) {
        if (candidateUserId == null) return null;
        // For our model, the candidate user is the intern; no indirection.
        return candidateUserId;
    }

    private boolean isSuperAdmin(User actor) {
        return actor.getRoles() != null
                && actor.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    /** Lookup an intern's user id via the lifecycle row id. Used by ExitService. */
    public Optional<UUID> internUserIdForLifecycle(UUID lifecycleId) {
        if (lifecycleId == null) return Optional.empty();
        return internLifecycleRepository.findById(lifecycleId)
                .map(InternLifecycle::getUserId);
    }
}
