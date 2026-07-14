package com.anvicorp.api.enums;

/**
 * Lifecycle for a Reporting Manager's Q&amp;A (viva) session on a project.
 *
 * <pre>
 *   SCHEDULED  RM picked a date + meeting link; project is PENDING_VIVA.
 *   CONDUCTED  RM captured questions + intern responses; awaiting sign-off.
 *   COMPLETED  Terminal — sign-off succeeded; project flipped to COMPLETED.
 *   RETURNED   RM bounced the project back to IN_PROGRESS; session is closed.
 * </pre>
 *
 * COMPLETED + RETURNED are both terminal — the project either moves forward
 * or restarts; a new {@link com.anvicorp.api.entity.QaSession} is created
 * for the next attempt.
 */
public enum QaSessionStatus {
    SCHEDULED,
    CONDUCTED,
    COMPLETED,
    RETURNED
}
