package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per intern invited to a {@link GroupSession}. Uniqueness on
 * {@code (group_session_id, intern_user_id)} prevents double-invites
 * from a create request with a duplicated participant id.
 */
@Entity
@Table(
        name = "group_session_participants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_group_session_participant",
                columnNames = {"group_session_id", "intern_user_id"}),
        indexes = {
                @Index(name = "idx_group_session_participant_intern",
                        columnList = "intern_user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupSessionParticipant {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_session_id", nullable = false)
    private GroupSession groupSession;

    /** {@code users.id} of the invited intern. */
    @Column(name = "intern_user_id", nullable = false)
    private UUID internUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
