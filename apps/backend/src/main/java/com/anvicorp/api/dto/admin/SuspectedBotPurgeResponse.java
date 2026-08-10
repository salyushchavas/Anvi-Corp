package com.anvicorp.api.dto.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for
 * {@code POST /api/v1/admin/users/purge-suspected-bots}.
 *
 * <p>The endpoint runs a two-phase flow — a default DRY-RUN that only
 * returns the matched accounts, then a CONFIRM call that actually
 * hard-deletes each one via the existing audited
 * {@code AdminUserService.deleteUser} → {@code hardPurge} path. This
 * DTO is used for both phases; the same shape lets the frontend render
 * a preview table then, on confirm, an outcome table.</p>
 */
public record SuspectedBotPurgeResponse(
        /** Echoes the caller's {@code ?confirm=} flag so the frontend
         *  knows which state it's in without redundant book-keeping. */
        boolean dryRun,
        /** Number of accounts the bot-signature query matched. Populated
         *  in both phases — it's the total the operator is reviewing on
         *  dry-run and the total attempted on confirm. */
        int matched,
        /** The matched accounts. Always present so the operator sees who
         *  would be / was affected. Ordered ascending by
         *  {@code createdAt} (oldest first) so a run against a growing
         *  wave of bots processes the earliest signups first. */
        List<BotCandidate> candidates,
        /** Confirm-phase only: number successfully purged (each via
         *  {@code AdminUserService.deleteUser}, which routes to the
         *  full audited FK-sweeping {@code hardPurge}). Zero on
         *  dry-run. */
        int purged,
        /** Confirm-phase only: per-account failures. Zero on dry-run,
         *  and empty on a fully-successful confirm. Each entry records
         *  the row id + the reason the delete failed so a partial
         *  failure is visible without inspecting logs. */
        List<PurgeFailure> failures
) {

    /** One row per bot signature hit. */
    public record BotCandidate(
            UUID id,
            String email,
            String fullName,
            Instant createdAt
    ) {}

    /** One row per per-account failure during the confirm phase. */
    public record PurgeFailure(
            UUID id,
            String email,
            String reason
    ) {}
}
