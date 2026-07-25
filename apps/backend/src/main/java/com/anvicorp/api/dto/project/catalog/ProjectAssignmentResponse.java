package com.anvicorp.api.dto.project.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.Difficulty;
import com.anvicorp.api.enums.ProjectAssignmentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectAssignmentResponse(
        UUID id,
        ProjectRef project,
        UserRef intern,
        UserRef assignedBy,
        LocalDate assignmentDate,
        LocalDate dueDate,
        String remarks,
        ProjectAssignmentStatus status,

        // ── Out-of-band GitHub-access tracking ──
        Boolean accessGranted,
        Instant accessGrantedAt,
        UserRef accessGrantedBy,

        // ── Status-transition timestamps ──
        Instant startedAt,
        Instant submittedAt,
        String submissionNotes,

        /** Latest {@code ProjectSubmission} row for this project (if any) —
         *  carries the deliverable links + trainer feedback so the intern's
         *  My Projects detail page can show what was submitted and what
         *  the trainer said. Null until the intern submits at least once. */
        LatestSubmission latestSubmission,

        /** Active {@code QaSession} for this project (status SCHEDULED or
         *  CONDUCTED) — surfaces the evaluator's Zoom join link + scheduled
         *  time on the intern's project tracker + dashboard Q&A card. Null
         *  when no session is scheduled. Host start_url is intentionally
         *  NOT included — that field is HOST-ONLY. */
        QaSummary qaSession,

        Instant createdAt,
        Instant updatedAt
) {
    public record ProjectRef(
            UUID id,
            String name,
            String techStack,
            Difficulty difficulty,
            String description,
            String requirements,
            String objectives,
            String deliverables,
            String instructions,
            Integer expectedDurationDays,
            LocalDate startDate,
            LocalDate endDate,
            RepositorySummary repository,
            /** KT (Knowledge Transfer) — null when not assigned (catalog-only). */
            KtSummary kt,
            /** Trainer-uploaded project brief / starter file. Null when the
             *  trainer didn't attach one (or attached only after the intern's
             *  DTO was cached). Download via
             *  {@code GET /api/v1/project-assignments/{id}/file} — that
             *  endpoint authorizes the assignment owner rather than the
             *  document owner. */
            List<ProjectFileRef> files
    ) {}

    public record ProjectFileRef(
            UUID id,
            String fileName,
            String mimeType,
            Long fileSize
    ) {}

    public record KtSummary(
            String status,            // NOT_DONE | DONE
            Instant completedAt,
            String meetingLink,
            String notes,
            String markedByName,
            /** Live KT session (Zoom) — null when the trainer hasn't
             *  scheduled one. zoomStartUrl is intentionally NOT included
             *  here (intern-side DTO must stay applicant-safe; the host
             *  link is only surfaced via the trainer-facing
             *  CatalogProjectResponse.KtSummary). */
            String zoomMeetingId,
            String zoomJoinUrl,
            Instant scheduledFor,
            Integer durationMinutes,
            String timezone
    ) {}

    public record RepositorySummary(
            String repositoryName,
            String repositoryUrl
    ) {}

    public record UserRef(
            UUID id,
            String fullName,
            String email,
            String githubUsername
    ) {}

    /**
     * Projection of {@code ProjectSubmission} relevant to the intern:
     * what they sent, when, what version, and whatever the trainer
     * decided/wrote. Trainer-private fields (scores, blockers note) are
     * intentionally left off — the intern surface only needs the public
     * decision + feedback.
     */
    /**
     * Intern-safe projection of the active {@code QaSession} (the
     * evaluator's Q&A / viva session). Mirrors the {@code KtSummary}
     * pattern — Zoom join URL + scheduled time + duration / timezone +
     * who scheduled it. {@code zoomStartUrl} is host-only and is NEVER
     * surfaced through this DTO.
     */
    public record QaSummary(
            UUID sessionId,
            String status,             // SCHEDULED | CONDUCTED
            Instant scheduledAt,
            Integer durationMinutes,
            String timezone,
            String meetingLink,        // free-text fallback (manual link if no Zoom)
            String zoomMeetingId,
            String zoomJoinUrl,
            String scheduledByName     // evaluator full name — for Model A copy
    ) {}

    public record LatestSubmission(
            UUID id,
            Integer version,
            List<String> links,
            String description,
            Instant submittedAt,
            String trainerDecision,    // ACCEPT | REQUEST_REVISION | ESCALATE | NO_ACTION_YET
            String trainerFeedback,    // verbatim text — shown to intern
            Instant reviewedAt,
            String reviewedByName,
            /** Optional supporting file the intern attached alongside the
             *  deliverable links. Null when the intern didn't upload one.
             *  Download via
             *  {@code GET /api/v1/project-submissions/{submissionId}/attachment}
             *  — RBAC gates on intern-owner + assigned trainer + SUPER_ADMIN. */
            SubmissionAttachmentRef attachment
    ) {}

    public record SubmissionAttachmentRef(
            UUID documentId,
            String fileName,
            String mimeType,
            Long fileSize
    ) {}
}
