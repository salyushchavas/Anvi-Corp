package com.anvicorp.api.todos;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's optional "hide this item" mark on a derived todo row.
 *
 * <p>Keyed off the DERIVED {@code todoKey} (e.g.
 * {@code "MANAGER_HIRE_APPROVAL:<candidateId>"}), NEVER off a
 * {@code user_notifications.id}, so:</p>
 * <ul>
 *   <li>The dismissal survives across polls of the same underlying row.</li>
 *   <li>When the underlying row auto-resolves (status flip inside the same
 *       {@code @Transactional} write endpoint), the pending-action query
 *       stops emitting the key. The dismissal row becomes an orphan —
 *       harmless; can be swept nightly.</li>
 *   <li>Optional by design: an item the user hasn't dismissed still
 *       shows; real completion happens automatically.</li>
 * </ul>
 */
@Entity
@Table(name = "user_todo_dismissals")
@IdClass(UserTodoDismissal.PK.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTodoDismissal {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "todo_key", nullable = false, length = 200)
    private String todoKey;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private UUID userId;
        private String todoKey;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(todoKey, pk.todoKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, todoKey);
        }
    }
}
