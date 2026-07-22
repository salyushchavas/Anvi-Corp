package com.anvicorp.api.dto.report;

import com.anvicorp.api.enums.WeeklyReportStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Single weekly report row. Shared between intern (own view) and supervisor
 * (intern's report list + review panel). No PII beyond {@code internName}
 * (the candidate's display name).
 *
 * <p>The {@code attachment*} fields are populated only when the intern
 * uploaded a supporting file. {@code attachmentDownloadUrl} is a stable
 * backend route (not a presigned URL) so both the intern (owner) and the
 * reviewer hit the same RBAC-gated endpoint.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WeeklyReportResponse {
    private UUID id;
    private UUID internCandidateId;
    private String internName;
    private LocalDate weekStart;
    private String completedWork;
    private String blockers;
    private String learningOutcomes;
    private String nextPlan;
    private WeeklyReportStatus status;
    private Instant submittedAt;
    private UUID reviewedById;
    private String reviewedByName;
    private String reviewNotes;
    private Instant reviewedAt;
    private Instant createdAt;
    private Instant updatedAt;

    /** {@code documents.id} — non-null only when an attachment is present. */
    private UUID attachmentDocumentId;
    /** Original file name the intern uploaded (e.g. {@code week-3-report.pdf}). */
    private String attachmentFileName;
    /** File size in bytes. */
    private Long attachmentFileSize;
    /** MIME type — one of the allow-listed PDF / doc / docx types. */
    private String attachmentMimeType;
    /**
     * Stable backend route that streams the bytes. Same URL for intern
     * (owner) and reviewer — RBAC is enforced at the endpoint. Callers
     * can wire this into an {@code <a href>} / {@code <embed>} without
     * URL-generation logic on the frontend. Null when no attachment.
     */
    private String attachmentDownloadUrl;
}
