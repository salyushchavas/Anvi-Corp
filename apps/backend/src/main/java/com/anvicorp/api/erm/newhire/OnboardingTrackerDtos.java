package com.anvicorp.api.erm.newhire;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes for the ERM "selected → active intern" onboarding tracker.
 * The tracker exposes a fixed 5-step pipeline with per-step statuses so
 * the frontend can render a stepper + a single "Next action" banner that
 * always tells the ERM where to look next.
 *
 * <p>The step IDs are part of the API contract — they're switched on in
 * the frontend to pick the right action component (modal launcher,
 * redirect link, waiting+reminder pair, gated activate button). Don't
 * rename them without coordinating with the frontend.</p>
 *
 * <p>The pipeline starts at "Assign documents" rather than "Offer sent".
 * Both offer-related steps were dropped: by the time a row appears in
 * the New Hire List, the offer is already SENT + SIGNED (that's the
 * entry condition for an InternLifecycle row to exist), so rendering
 * them as separate steps was just noise.</p>
 */
public final class OnboardingTrackerDtos {

    private OnboardingTrackerDtos() {}

    /** Fixed step IDs. Order in the enum is the display order. */
    public enum StepId {
        DOCS_ASSIGNED,
        DOCS_VERIFIED,
        TEAM_NOTIFIED,
        MAIL_AND_JOINING,
        ACTIVATE
    }

    /**
     * Visual status the frontend renders. Steps are evaluated independently;
     * the service also picks a single {@code currentStepId} (first non-DONE)
     * that the "Next action" banner anchors on.
     */
    public enum StepStatus {
        /** Step complete. Tracker node shows a check; no action. */
        DONE,
        /** This is the next ERM action. Tracker node is highlighted; the
         *  Next banner mounts the step's action component. */
        CURRENT,
        /** Action sits with the intern (offer signature, doc submission).
         *  Banner shows a waiting chip + a "Send reminder" button. */
        WAITING_INTERN,
        /** Step isn't reachable yet (earlier steps incomplete). Greyed. */
        PENDING,
        /** Activation only — disabled with "N steps left" until all earlier
         *  steps are DONE. */
        LOCKED
    }

    /** Who completes the step — drives the action UI shape. */
    public enum StepActor { ERM, INTERN, SYSTEM }

    /**
     * Shape of the next-action affordance in the banner. Frontend switches
     * on this to render the right component:
     * <ul>
     *   <li>{@code MODAL} — open a tracker modal (no navigation)</li>
     *   <li>{@code REDIRECT} — link to an existing screen; user comes back</li>
     *   <li>{@code WAIT_REMINDER} — waiting chip + "Send reminder" button</li>
     *   <li>{@code GATED} — disabled button with "N steps left" copy</li>
     *   <li>{@code NONE} — step already DONE, no banner action</li>
     * </ul>
     */
    public enum ActionType { MODAL, REDIRECT, WAIT_REMINDER, GATED, NONE }

    /**
     * One row on the stepper. {@code subTasks} is only populated for
     * composite steps (today: only MAIL_AND_JOINING — mail ID + joining
     * date, tracked as two checkboxes inside the same step).
     */
    public record OnboardingStep(
            StepId id,
            String label,
            StepStatus status,
            StepActor actor,
            ActionType actionType,
            Instant completedAt,
            String helpText,
            String redirectHref,
            List<SubTask> subTasks) {}

    /** Sub-task (composite step). {@code done} drives the inner checkbox. */
    public record SubTask(String label, boolean done) {}

    /**
     * Top-level tracker payload. {@code stepsRemaining} is a convenience
     * the list page uses for the "N/5 · needs X" badge so callers don't
     * have to count DONE entries themselves.
     */
    public record OnboardingTracker(
            UUID internLifecycleId,
            List<OnboardingStep> steps,
            StepId currentStepId,
            int stepsCompleted,
            int stepsTotal,
            int stepsRemaining,
            String nextStepLabel,
            boolean canActivate) {}
}
