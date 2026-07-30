package com.anvicorp.api.todos;

import java.time.Instant;
import java.util.List;

/**
 * Shared payload for the role-aware to-do panel + login pop-up gate.
 *
 * <p>Every bucket is a live pending-action query that filters on a status
 * column flipped inside the same {@code @Transactional} write endpoint —
 * so items auto-drop from the panel on next fetch when the underlying work
 * is done (see {@code ManagerTodoService} + {@code EvaluatorTodoService}).
 * A user's optional dismissal is decoration only: it stamps
 * {@code dismissed=true} but never removes the row.</p>
 *
 * <p>{@link #hasNewSinceLastSeen} is the gate the frontend uses to fire the
 * once-per-session login pop-up: TRUE when any {@link TodoItem#firstAppearedAt}
 * is after {@link #lastSeenAt}, OR when {@code lastSeenAt} is null and any
 * bucket has count &gt; 0.</p>
 */
public record TodoPanelResponse(
        String role,
        List<TodoBucket> buckets,
        long totalCount,
        boolean hasNewSinceLastSeen,
        Instant lastSeenAt
) {

    public record TodoBucket(
            /** Stable machine key, e.g. "MANAGER_HIRE_APPROVAL". */
            String bucketKey,
            /** Human label shown in the panel + pop-up row. */
            String label,
            /** Lucide icon name the frontend maps to a component. */
            String icon,
            long count,
            /** Deep-link the row / "review all" button points at. */
            String actionUrl,
            /** INFO | WARN | URGENT — drives the pop-up row accent + panel
             *  badge colour. */
            String severity,
            /** Top-N preview items (may be empty when count > 0 if the
             *  bucket exposes only a count query). */
            List<TodoItem> topItems
    ) {}

    public record TodoItem(
            /** Composite key {@code "<BUCKET_KEY>:<domain-row-id>"}. Used
             *  as the primary key on {@code user_todo_dismissals} so a
             *  dismissal survives a re-fetch and is discarded automatically
             *  once the underlying row auto-resolves (the key stops being
             *  emitted by the pending-action query). */
            String todoKey,
            /** Primary label, typically the person / subject. */
            String label,
            /** Secondary line — "Waiting since 3d ago" etc. Nullable. */
            String subLabel,
            /** Deep-link to the individual work item. */
            String actionUrl,
            /** First time this item entered the pending state — used to
             *  gate the pop-up against the user's {@code lastSeenAt}. */
            Instant firstAppearedAt,
            /** TRUE when the user has explicitly hidden this row via
             *  {@code POST /api/v1/todos/{todoKey}/dismiss}. Auto-resolve
             *  remains authoritative — the row stops appearing when the
             *  underlying entity's status flips. */
            boolean dismissed
    ) {}
}
