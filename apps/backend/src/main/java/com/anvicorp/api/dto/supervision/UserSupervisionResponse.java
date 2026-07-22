package com.anvicorp.api.dto.supervision;

import com.anvicorp.api.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Consolidated per-user supervision view for SUPER_ADMIN.
 *
 * <h2>PII boundary</h2>
 * STATUS-only on this overview. NO decrypted SSN / A-Number / I-9 document
 * numbers / DOB / foreign passport. The compliance fields surface enum
 * labels and plain dates (work-auth expiration, first day of employment)
 * that are already exposed elsewhere as non-encrypted columns. The
 * existing gated detail pages remain the only path to decrypted PII —
 * super admin can open them from the deep-links, and that navigation is
 * audited via the existing entity controllers' read-access audits.
 *
 * <h2>Role-contextual sections</h2>
 * Exactly one of {@code candidateContext} / {@code supervisorContext} is
 * populated; the others stay null. EXECUTIVE has none — the activity
 * timeline alone covers the read-only role's footprint.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSupervisionResponse {

    private ProfileBlock profile;

    /** Last N audit events where this user was the actor (newest first). */
    private List<ActivityRow> activity;

    /**
     * Top action types this user has performed, with counts. Computed from
     * the full audit log (not just the trimmed activity list) so the rollup
     * stays accurate even when the timeline is truncated.
     */
    private List<ActionCount> topActions;

    /** Populated only when the target user is APPLICANT or INTERN. */
    private CandidateContext candidateContext;

    /** Populated only when the target user is TECHNICAL_EVALUATOR. */
    private SupervisorContext supervisorContext;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProfileBlock {
        private UUID id;
        private String name;
        private String email;
        private Set<UserRole> roles;
        private Boolean active;
        private String applicantId;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ActivityRow {
        private Instant timestamp;
        private String action;
        private String entityType;
        private UUID entityId;
        /** Truncated JSON snapshot — never includes decrypted PII. */
        private String details;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ActionCount {
        private String action;
        private long count;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CandidateContext {
        private UUID candidateId;
        private List<ApplicationSummary> applications;
        /** Most-recent engagement summary, or null. */
        private EngagementSummary engagement;
        /** I-9 STATUS + plain dates only (no SSN / A-Number / doc numbers). */
        private ComplianceStatus compliance;
        /** Newest reports first; capped. */
        private List<ReportSummary> weeklyReports;
        /** Newest timesheets first; capped. */
        private List<TimesheetSummary> timesheets;
        /** Distinct count of materials this intern has acknowledged. */
        private long materialsAcknowledgedCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApplicationSummary {
        private UUID id;
        private String position;
        private String entityName;
        private String status;
        private Instant appliedAt;
        private String href;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EngagementSummary {
        private UUID id;
        private String status;
        private LocalDate plannedStartDate;
        private LocalDate actualStartDate;
        private String supervisorName;
        private String entityName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ComplianceStatus {
        private String i9Status;
        private LocalDate i9FirstDayOfEmployment;
        private LocalDate i9WorkAuthExpirationDate;
        /** Deep-link to the gated I-9 detail page (audited on open). */
        private String i9DetailHref;

        private String i983Status;
        private LocalDate i983OptEndDate;
        private String i983DetailHref;

        private String everifyStatus;
        private String everifyDetailHref;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReportSummary {
        private UUID id;
        private LocalDate weekStart;
        private String status;
        private Instant submittedAt;
        private Instant reviewedAt;
        /**
         * Stable backend route to stream the attachment; null when the
         * report has no attachment. Same URL the intern-owner and
         * assigned supervisor hit — RBAC is at the endpoint.
         */
        private String attachmentDownloadUrl;
        /** Original file name — used as the visible link label. Null when no attachment. */
        private String attachmentFileName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimesheetSummary {
        private UUID id;
        private LocalDate weekStart;
        private String status;
        private String hours;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SupervisorContext {
        private List<InternMini> assignedInterns;
        private long activeInternsCount;
        private long reportsAwaitingReview;
        private long timesheetsAwaitingApproval;
        private long upcomingEvaluations;
        /** Count of materials this supervisor has published (DRAFT + RELEASED). */
        private long materialsPublished;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InternMini {
        private UUID candidateId;
        private String name;
        private String position;
        private String engagementStatus;
        private String reviewHref;
    }

}
