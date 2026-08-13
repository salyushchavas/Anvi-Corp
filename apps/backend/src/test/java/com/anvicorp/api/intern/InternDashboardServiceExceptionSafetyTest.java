package com.anvicorp.api.intern;

import com.anvicorp.api.enums.InternLifecycleStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pattern-D fix regression — the "Evaluation Cycle" terminal step must
 * not falsely regress to UPCOMING on an INACTIVE intern when the
 * evaluator-service lookup transiently fails. The safeguard lives in
 * {@link InternDashboardService#safeHasPublishedEvaluation}: the
 * exception path returns {@code true} for INACTIVE (past the cycle by
 * definition, so a lookup blip must not visually undo it) and
 * {@code false} for every other status (an ACTIVE intern with a failed
 * lookup must not be falsely marked done — the step should surface as
 * UPCOMING/ACTIVE until we actually know).
 *
 * <p>The three cases cover the branch matrix on the exception path
 * (INACTIVE → true, ACTIVE → false, pre-active → false) plus a success
 * path (lookup returns whatever the service reports; the safeguard
 * doesn't override a successful call).</p>
 */
class InternDashboardServiceExceptionSafetyTest {

    private static final UUID UID = UUID.randomUUID();

    /** Evaluator-service stub that always throws — simulates a transient
     *  DB / network blip on the internHasPublishedEvaluation path. */
    private static InternEvaluationService throwingService() {
        return new StubEvaluationService(true, false);
    }

    /** Evaluator-service stub that reports the given value cleanly. */
    private static InternEvaluationService reportingService(boolean value) {
        return new StubEvaluationService(false, value);
    }

    private static InternDashboardService svcWith(InternEvaluationService eval) {
        // Only internEvaluationService is exercised by the SUT — every other
        // collaborator is safe to null-pass because safeHasPublishedEvaluation
        // never touches them. Fine to skip Spring wiring.
        return new InternDashboardService(
                null, null, eval, null, null, null, null, null, null, null);
    }

    // ── Exception-path safety ─────────────────────────────────────────

    @Test
    void inactive_intern_with_failing_lookup_reports_cycle_done() {
        InternDashboardService svc = svcWith(throwingService());
        assertTrue(svc.safeHasPublishedEvaluation(
                        UID, InternLifecycleStatus.INACTIVE_INTERN),
                "INACTIVE intern must NOT regress to 'no eval published' when "
                        + "the evaluator lookup transiently fails — the terminal "
                        + "step would render UPCOMING (the #22-lineage bug).");
    }

    @Test
    void active_intern_with_failing_lookup_does_not_falsely_report_cycle_done() {
        InternDashboardService svc = svcWith(throwingService());
        assertFalse(svc.safeHasPublishedEvaluation(
                        UID, InternLifecycleStatus.ACTIVE_INTERN),
                "ACTIVE intern with a failed lookup must NOT be marked as "
                        + "having a published evaluation — the fix is INACTIVE-only, "
                        + "we cannot falsely advance a live intern's terminal step.");
    }

    @Test
    void pre_active_intern_with_failing_lookup_does_not_falsely_report_cycle_done() {
        InternDashboardService svc = svcWith(throwingService());
        // Sample a couple of pre-active statuses to lock in the invariant.
        assertFalse(svc.safeHasPublishedEvaluation(
                UID, InternLifecycleStatus.OFFER_SIGNED));
        assertFalse(svc.safeHasPublishedEvaluation(
                UID, InternLifecycleStatus.ONBOARDING_ACCEPTED));
        assertFalse(svc.safeHasPublishedEvaluation(
                UID, InternLifecycleStatus.EMPLOYEE_ID_CREATED));
    }

    // ── Success path is not overridden ────────────────────────────────

    @Test
    void success_path_returns_service_value_unchanged_for_active() {
        InternDashboardService trueSvc = svcWith(reportingService(true));
        InternDashboardService falseSvc = svcWith(reportingService(false));
        assertTrue(trueSvc.safeHasPublishedEvaluation(
                UID, InternLifecycleStatus.ACTIVE_INTERN));
        assertFalse(falseSvc.safeHasPublishedEvaluation(
                UID, InternLifecycleStatus.ACTIVE_INTERN));
    }

    @Test
    void success_path_returns_service_value_unchanged_for_inactive() {
        // When the service DOES answer, we honour it — the INACTIVE
        // safeguard is exception-path only. An INACTIVE intern who
        // actually has zero published evaluations still legitimately
        // gets `false` from the service; the OR-with-INACTIVE_INTERN at
        // the buildStepper predicate still marks the terminal step done.
        InternDashboardService falseSvc = svcWith(reportingService(false));
        assertFalse(falseSvc.safeHasPublishedEvaluation(
                UID, InternLifecycleStatus.INACTIVE_INTERN));
    }

    // ── Stub ──────────────────────────────────────────────────────────

    /** Minimal InternEvaluationService subclass. Bypasses Spring wiring
     *  by passing nulls into the parent constructor (the SUT only calls
     *  {@code internHasPublishedEvaluation}, which we override). */
    private static final class StubEvaluationService extends InternEvaluationService {
        private final boolean shouldThrow;
        private final boolean value;

        StubEvaluationService(boolean shouldThrow, boolean value) {
            // 11 nulls match InternEvaluationService's @RequiredArgsConstructor
            // arity. Only internHasPublishedEvaluation is exercised, and it
            // is overridden below, so no field is dereferenced.
            super(null, null, null, null, null, null,
                    null, null, null, null, null);
            this.shouldThrow = shouldThrow;
            this.value = value;
        }

        @Override
        public boolean internHasPublishedEvaluation(UUID userId) {
            if (shouldThrow) {
                throw new RuntimeException("simulated DB blip");
            }
            return value;
        }
    }
}
