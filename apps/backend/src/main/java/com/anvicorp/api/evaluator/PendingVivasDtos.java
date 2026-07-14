package com.anvicorp.api.evaluator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the Evaluator's pending-Q&A queue — projects whose status is
 * {@code PENDING_VIVA} (trainer has approved, awaiting evaluator's Q&A
 * session + final approval).
 */
public final class PendingVivasDtos {

    private PendingVivasDtos() {}

    /**
     * One row per project awaiting Q&A. {@code activeSession} is the most
     * recent SCHEDULED or CONDUCTED {@code QaSession} — null when no
     * session has been scheduled yet (the evaluator's first action is to
     * schedule one).
     */
    public record PendingVivaRow(
            UUID projectId,
            String projectTitle,
            String techStack,
            String monthYear,
            Short projectNumber,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            Instant submittedAt,
            long hoursWaiting,
            String trainerFeedback,
            ActiveSession activeSession
    ) {}

    public record ActiveSession(
            UUID sessionId,
            String status,
            Instant scheduledAt,
            String meetingLink,
            String zoomMeetingId,
            String zoomJoinUrl,
            // HOST-ONLY — the frontend re-fetches a fresh start_url via
            // /api/v1/meetings/{id}/host-start; this stored copy only
            // backs the "stored copy exists" error-state hint.
            String zoomStartUrl
    ) {}

    public record PendingVivasResponse(
            List<PendingVivaRow> items,
            int total
    ) {}
}
