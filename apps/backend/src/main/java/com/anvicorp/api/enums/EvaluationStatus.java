package com.anvicorp.api.enums;

/**
 * Evaluation lifecycle. Distinct from {@link EvaluationSessionStatus} —
 * which describes scheduled check-in sessions — because periodic evaluations
 * carry a draft phase the supervisor refines before locking.
 *
 * <pre>
 *   DRAFT      Supervisor is composing; can edit + adjust rubric scores.
 *   FINALIZED  Terminal. Locked — no edits, intern can see it.
 * </pre>
 */
public enum EvaluationStatus {
    DRAFT,
    FINALIZED
}
