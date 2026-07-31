package com.anvicorp.api.recordings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the unified Recording Gallery — a role-aware file-explorer
 * tree used by ERM / MANAGER / EVALUATOR. Server-side role filtering
 * enforces the visibility matrix:
 * <ul>
 *   <li>ERM + MANAGER see every intern's interview + evaluation
 *       recordings at any approval status.</li>
 *   <li>EVALUATOR sees ONLY their own evaluations with
 *       {@code approvalStatus = APPROVED}, and no interview recordings.</li>
 * </ul>
 * The frontend renders folder-style navigation off this shape:
 * Intern → Type (Interview | Monthly evaluations) → [Month → Project]
 * → video files.
 */
public final class RecordingGalleryDtos {

    private RecordingGalleryDtos() {}

    /** One playable recording — the common leaf shape for both
     *  interviews (flat under an intern) and evaluations (nested by
     *  month + project). */
    public record RecordingFile(
            UUID evaluationId,      // null for interview recordings
            UUID documentId,
            String fileName,
            String mimeType,
            Long fileSizeBytes,
            Instant uploadedAt,
            String uploadedByName,
            /** APPROVED / PENDING_APPROVAL / REVISION_REQUESTED — null
             *  for interview recordings. */
            String approvalStatus,
            /** P1 / P2 / P1_P2 — null for interview recordings. */
            String scope,
            /** Relative API path the frontend fetches to get a
             *  presigned playback URL. Set per-file so the same client
             *  code plays interview OR evaluation recordings without
             *  branching. */
            String downloadUrlPath
    ) {}

    public record ProjectFolder(
            /** P1 / P2 / P1_P2 — folder label. */
            String scope,
            UUID projectId,
            String projectTitle,
            /** Human-friendly folder name the UI should display in both
             *  the tile and the breadcrumb leaf. Server-computed so the
             *  client doesn't have to reason about "is this projectTitle
             *  a real name, an id-shaped placeholder, or missing?".
             *  Examples: "P1 · Real Project Name", "P1 + P2 · Another
             *  Project", "P1" (when the project has no real title). */
            String folderLabel,
            List<RecordingFile> files
    ) {}

    public record MonthFolder(
            /** yyyy-MM. */
            String monthYear,
            /** Human-friendly month label the UI should display in both
             *  the tile and the breadcrumb — e.g. "July 2026". Never
             *  the literal string "unknown"; if truly underivable, the
             *  server falls back to the recording's upload month so the
             *  gallery always shows a real month. */
            String monthLabel,
            List<ProjectFolder> projects
    ) {}

    public record InternFolder(
            UUID internUserId,
            String internName,
            String employeeId,
            /** Flat list of interview recordings under this intern.
             *  Empty for the evaluator role. */
            List<RecordingFile> interviewRecordings,
            /** Monthly evaluations grouped by month → project. */
            List<MonthFolder> evaluationMonths,
            /** Convenience count for the folder card badge. */
            int totalRecordings
    ) {}

    public record GalleryResponse(
            List<InternFolder> interns
    ) {}
}
