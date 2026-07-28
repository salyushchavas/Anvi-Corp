package com.anvicorp.api.project.referenceqa;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Trainer-authored reference Q&amp;A pairs attached to a Project. Written
 * by the trainer on the review screen; read (read-only) by the evaluator
 * on the compose screen for the intern's linked project. Neutral naming
 * throughout — this is trainer-provided reference material, no framing
 * that implies the evaluator lacks knowledge.
 */
public final class ReferenceQaDtos {

    private ReferenceQaDtos() {}

    /** One question/answer pair. {@code order} is 0-indexed, monotonic. */
    public record ReferenceQaPair(
            @NotBlank @Size(max = 2000) String question,
            @NotBlank @Size(max = 5000) String answer,
            Integer order
    ) {}

    /** Full-list write payload — the trainer edits the whole list together
     *  and posts it as one atomic replacement. Pass an empty list to clear. */
    public record ReferenceQaWriteRequest(
            @Valid List<ReferenceQaPair> pairs
    ) {}

    public record ReferenceQaResponse(
            /** {@code Project.id} the Q&amp;A is attached to. */
            java.util.UUID projectId,
            /** Newest edit timestamp on the underlying project row. */
            java.time.Instant projectUpdatedAt,
            List<ReferenceQaPair> pairs
    ) {}
}
