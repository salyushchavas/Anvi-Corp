package com.anvicorp.api.erm.interview;

import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.InterviewType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ERM Phase 3 — DTO surface for the interview scheduler + decision center. */
public final class ErmInterviewDtos {

    private ErmInterviewDtos() {}

    public record ErmInterviewRow(
            UUID interviewId,
            UUID applicationId,
            String applicantName,
            String applicantId,
            String jobTitle,
            String jobType,
            Instant scheduledAt,
            Integer durationMinutes,
            String timezone,
            InterviewStatus status,
            String decision,
            UUID interviewerId,
            String interviewerName,
            int rescheduleCount
    ) {}

    public record ErmInterviewListPage(
            List<ErmInterviewRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record CalendarEntry(
            UUID interviewId,
            UUID applicationId,
            String applicantName,
            String jobTitle,
            Instant scheduledAt,
            Integer durationMinutes,
            String timezone,
            InterviewStatus status,
            String decision,
            String interviewerName
    ) {}

    /**
     * Role-shaped detail. Field RBAC handled at the service:
     *  - INTERN: zoomStartUrl, internalNotes, decisionReasonText, scores → null
     *  - TRAINER / EVALUATOR (not on panel): null on the same fields
     *  - MANAGER: scores + recommendation present; internalNotes + decisionReasonText null
     *  - ERM / SUPER_ADMIN / interviewer / panel: full payload
     */
    /**
     * Outcome of the most recent Zoom call attempt for the interview.
     * Surfaced on {@link ErmInterviewDetail} so the ERM UI can render
     * a "Zoom not configured" / "Zoom failed: …" / "Manual link in use"
     * banner instead of leaving the user to guess why the join link is
     * missing.
     *
     * <ul>
     *   <li>{@code OK} — meeting created and join_url stored.</li>
     *   <li>{@code MANUAL_LINK} — ERM supplied a manual link; Zoom API not called.</li>
     *   <li>{@code NOT_CONFIGURED} — Zoom credentials are not set on the server.</li>
     *   <li>{@code DISABLED} — ZOOM_ENABLED=false force-disable is active.</li>
     *   <li>{@code CREATE_FAILED} — Zoom API returned an error on createMeeting.</li>
     *   <li>{@code UPDATE_FAILED} — Zoom API returned an error on updateMeeting.</li>
     *   <li>{@code UNKNOWN} — read-only view; no recent attempt to report.</li>
     * </ul>
     */
    public enum ZoomStatus {
        OK, MANUAL_LINK, NOT_CONFIGURED, DISABLED, CREATE_FAILED, UPDATE_FAILED, UNKNOWN
    }

    public record ErmInterviewDetail(
            UUID id,
            InterviewStatus status,
            InterviewType type,
            Instant scheduledAt,
            Integer durationMinutes,
            String timezone,
            String prepInstructions,
            String zoomJoinUrl,
            String zoomStartUrl,             // ERM/interviewer only
            String zoomPassword,             // ERM/interviewer only
            String zoomMeetingId,            // Phase 2 — String since meeting providers differ on id shape
            /** Phase: surfaces the most recent Zoom outcome so the UI can
             *  render a clear banner + Regenerate action when needed. */
            ZoomStatus zoomStatus,
            /** Truncated Zoom error message for CREATE_FAILED / UPDATE_FAILED.
             *  Never contains secrets — bodies are pre-truncated by ZoomService. */
            String zoomErrorMessage,
            String decision,
            String overallRecommendation,
            Integer technicalScore,
            Integer communicationScore,
            Integer culturalFitScore,
            String applicantVisibleNotes,
            String internalNotes,            // ERM/MANAGER only — but actually ERM-only per spec
            String decisionReasonCode,
            String decisionReasonText,       // ERM-only
            /** PENDING | APPROVED | REJECTED — manager hire-approval gate.
             *  Null on COMPLETED rows that pre-date the gate (the
             *  SchemaFixupRunner backfill is best-effort). */
            String managerHireDecision,
            Instant managerHireDecisionAt,
            String managerHireDecisionNote,
            int rescheduleCount,
            String lastRescheduleReasonCode,
            String lastRescheduleReasonText, // ERM-only
            Instant lastRescheduledAt,
            String cancellationReasonCode,
            String cancellationReasonText,   // ERM-only
            Instant cancelledAt,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            ApplicantView applicant,
            JobView job,
            InterviewerView interviewer,
            List<InterviewerView> panel,
            List<EventLogEntry> history,
            AvailableActions availableActions,
            String callerRole
    ) {}

    public record ApplicantView(
            UUID userId,
            String firstName,
            String lastName,
            String email,
            String applicantId,
            UUID applicationId,
            String applicationStatus
    ) {}

    public record JobView(
            UUID id,
            String title,
            String jobType,
            String location
    ) {}

    public record InterviewerView(
            UUID userId,
            String fullName,
            String email,
            String role,
            String zoomEmail,
            boolean hasZoomEmail
    ) {}

    public record EventLogEntry(
            UUID id,
            String eventType,
            String reasonCode,
            String reasonText,            // ERM-only — stripped in non-ERM views
            String payloadJson,
            UUID actorUserId,
            String actorName,
            Instant createdAt
    ) {}

    public record AvailableActions(
            boolean canComplete,
            boolean canReschedule,
            boolean canChangeInterviewer,
            boolean canCancel,
            boolean canEditNotes,
            boolean canSendReminder
    ) {}

    public record ErmCreateInterviewRequest(
            UUID applicationId,
            Instant scheduledFor,
            Integer durationMinutes,
            String timezone,
            UUID interviewerId,
            List<UUID> panelInterviewerIds,
            String prepInstructions,
            String templateKey,
            String manualZoomLink,
            InterviewType type
    ) {}

    public record ErmRescheduleRequest(
            Instant scheduledFor,
            Integer durationMinutes,
            /** Phase 1.7 — optional new IANA zone for the rescheduled
             *  slot. Omitted means "keep the interview's existing
             *  timezone"; supplied means "store this and use it for the
             *  rescheduled time / Zoom payload". */
            String timezone,
            String reasonCode,
            String reasonText,
            Boolean notifyApplicant,
            Boolean notifyInterviewer
    ) {}

    public record ChangeInterviewerRequest(UUID interviewerId) {}

    /**
     * Manager hire-approval gate: the ERM no longer decides
     * SELECTED/HOLD/REJECTED — it submits the scorecard + an optional
     * recommendation, then the candidate enters
     * {@code managerHireDecision = PENDING} and a Manager actions the
     * hire from the Hire Approvals queue.
     */
    public record ErmCompleteRequest(
            Integer technicalScore,
            Integer communicationScore,
            Integer culturalFitScore,
            String overallRecommendation,          // STRONG_HIRE | HIRE | NO_HIRE | STRONG_NO_HIRE
            String applicantVisibleNotes,
            String internalNotes
    ) {}

    public record ErmCancelRequest(
            String reasonCode,
            String reasonText,
            Boolean notifyApplicant
    ) {}

    public record NotesRequest(
            String applicantVisibleNotes,
            String internalNotes
    ) {}

    public record ReasonCodeGroup(
            String category,
            List<ReasonCodeOption> options
    ) {}

    public record ReasonCodeOption(
            String code,
            String label,
            boolean requiresFreeText
    ) {}
}
