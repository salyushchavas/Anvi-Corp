package com.anvicorp.api.dto.project.catalog;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Intern's submission payload for {@code POST /api/v1/project-assignments/{id}/submit}.
 *
 * <p>{@code deliverableLinks} is the primary signal — typically a GitHub
 * PR / repo URL plus a deployed-demo URL. Each entry is validated as a
 * parseable absolute URL server-side. {@code submissionNotes} captures
 * what changed this round (intern-facing memo + Trainer review context).
 * {@code attachmentDocumentId} points at a pre-uploaded document row
 * (see {@code POST /project-assignments/{id}/submission-attachment} —
 * that endpoint returns the id).</p>
 *
 * <p>All three fields are individually optional, but the service rejects
 * a submission that supplies none of them (nothing to review).</p>
 */
public record SubmitAssignmentRequest(
        @Size(max = 5000) String submissionNotes,
        List<String> deliverableLinks,
        /** {@code documents.id} — non-null when the intern uploaded a file. */
        UUID attachmentDocumentId
) {}
