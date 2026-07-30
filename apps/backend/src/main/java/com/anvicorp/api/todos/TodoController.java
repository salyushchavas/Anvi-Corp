package com.anvicorp.api.todos;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Shared to-do surface — endpoints that apply to every role. Per-role
 * aggregators ({@code GET /api/v1/{role}/todos}) live in their own
 * controllers ({@code ManagerTodoController}, {@code EvaluatorTodoController})
 * because they compose role-specific pending-action queries.
 */
@RestController
@RequestMapping("/api/v1/todos")
@RequiredArgsConstructor
public class TodoController {

    private final UserRepository userRepository;
    private final UserTodoDismissalRepository dismissals;

    /**
     * Stamps {@code users.todo_panel_last_seen_at = now()} for the caller.
     * The frontend calls this when the login pop-up is dismissed (Dismiss
     * button, or "View all in to-do") so it won't re-fire this session for
     * the same set of pending actions. Subsequent items still trigger it —
     * the pop-up gate is "any todo firstAppearedAt &gt; lastSeenAt".
     */
    @PostMapping("/seen")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, Object> markSeen(@AuthenticationPrincipal User caller) {
        User row = userRepository.findById(caller.getId()).orElse(null);
        if (row == null) return Map.of("ok", false);
        Instant now = Instant.now();
        row.setTodoPanelLastSeenAt(now);
        userRepository.save(row);
        return Map.of("ok", true, "lastSeenAt", now);
    }

    /**
     * Optional per-user hide. Keyed off the derived {@code todoKey} so a
     * dismissal survives across polls and becomes an orphan once the
     * underlying entity resolves (the query stops emitting the key). Not
     * required — auto-resolve owns the truth; this is UX only.
     */
    @PostMapping("/{todoKey}/dismiss")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, Object> dismiss(@PathVariable String todoKey,
                                        @AuthenticationPrincipal User caller) {
        if (todoKey == null || todoKey.isBlank()) {
            return Map.of("ok", false, "reason", "empty-key");
        }
        UserTodoDismissal.PK pk = new UserTodoDismissal.PK(caller.getId(), todoKey);
        if (dismissals.existsById(pk)) {
            return Map.of("ok", true, "existed", true);
        }
        dismissals.save(UserTodoDismissal.builder()
                .userId(caller.getId())
                .todoKey(todoKey)
                .dismissedAt(Instant.now())
                .build());
        return Map.of("ok", true, "existed", false);
    }

    /** Undo a dismissal so the row is visible again. */
    @DeleteMapping("/{todoKey}/dismiss")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, Object> undismiss(@PathVariable String todoKey,
                                          @AuthenticationPrincipal User caller) {
        int n = dismissals.deleteByUserAndKey(caller.getId(), todoKey);
        return Map.of("ok", true, "removed", n);
    }
}
