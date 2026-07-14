package com.anvicorp.api.entity;

import com.anvicorp.api.enums.ScreeningStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One screening per Application (1:1, enforced via the unique constraint on
 * {@code application_id}). Lifecycle: SENT -> COMPLETED. Score/maxScore are
 * populated on submit; null while SENT.
 */
@Entity
@Table(name = "screenings",
        uniqueConstraints = @UniqueConstraint(columnNames = "application_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Screening {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScreeningStatus status;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Sum of awarded points across scorable answers; null while SENT. */
    private Integer score;

    /** Sum of {@code points} across SINGLE_CHOICE questions at submission time. */
    @Column(name = "max_score")
    private Integer maxScore;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (sentAt == null) sentAt = now;
        if (status == null) status = ScreeningStatus.SENT;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
