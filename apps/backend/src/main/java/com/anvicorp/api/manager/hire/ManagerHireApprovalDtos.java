package com.anvicorp.api.manager.hire;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the Manager Hire Approvals surface.
 *
 * <p>The queue and detail rows pull from {@code interviews} where
 * {@code status = COMPLETED} and {@code manager_hire_decision = 'PENDING'}.
 * The detail view carries the ERM-submitted scorecard + recommendation
 * so the manager can decide hire / no-hire without round-tripping to
 * the ERM interview page.</p>
 */
public final class ManagerHireApprovalDtos {

    private ManagerHireApprovalDtos() {}

    public record HireApprovalRow(
            UUID interviewId,
            UUID applicationId,
            UUID candidateUserId,
            String candidateName,
            String candidateEmail,
            String jobTitle,
            String technology,
            Instant scorecardSubmittedAt,
            long hoursWaiting,
            Integer technicalScore,
            Integer communicationScore,
            Integer culturalFitScore,
            String overallRecommendation,
            /** PENDING | HOLD — final APPROVED/REJECTED rows never appear
             *  in the queue. Lets the row badge distinguish a fresh
             *  pending review from a previously-parked HOLD. */
            String managerHireDecision
    ) {}

    public record HireApprovalListPage(
            List<HireApprovalRow> items,
            int page, int pageSize, long totalElements, int totalPages
    ) {}

    public record HireApprovalDetail(
            UUID interviewId,
            UUID applicationId,
            UUID candidateUserId,
            String candidateName,
            String candidateEmail,
            String jobTitle,
            String technology,
            Instant interviewScheduledAt,
            Instant scorecardSubmittedAt,
            String scorecardSubmittedByName,
            Integer technicalScore,
            Integer communicationScore,
            Integer culturalFitScore,
            String overallRecommendation,
            /** ERM-only internal notes — manager-visible (manager is a
             *  senior internal reviewer, not the applicant). */
            String internalNotes,
            /** Applicant-safe outcome message the ERM drafted. */
            String applicantVisibleNotes,
            String managerHireDecision,
            Instant managerHireDecisionAt,
            String managerHireDecisionNote,
            /** Presigned S3 GET URL for the ERM-uploaded interview
             *  recording, or {@code null} when no recording is attached
             *  / S3 is not configured / the Document row was soft-deleted.
             *  URL TTL matches
             *  {@link com.anvicorp.api.erm.interview.ErmInterviewRecordingService#PRESIGN_DOWNLOAD_TTL}
             *  — the frontend just reloads the page to refresh. */
            String zoomRecordingUrl,
            /** Resume on the application this interview was for. Null when
             *  the candidate applied without one (rare). Drives the
             *  inline-PDF preview on the manager hire-decision page; the
             *  download URL points at {@code GET /api/v1/resumes/{id}/download},
             *  which is RBAC-gated to MANAGER + the row-level
             *  "linked to an application visible to me" check. */
            ResumeView resume
    ) {}

    public record ResumeView(
            UUID documentId,
            String fileName,
            Long fileSize,
            String mimeType,
            String downloadUrl
    ) {}

    public record HireApprovalDecisionRequest(
            /** Optional manager rationale shown to the ERM. */
            String note
    ) {}
}
