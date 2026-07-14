package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-day cell on a weekly {@link Timesheet}. Additive — the legacy weekly
 * total ({@code Timesheet.hours} + {@code Timesheet.description}) stays the
 * source of truth for older rows and the existing supervised flow. Day rows
 * coexist; when present the parent's {@code hours} is the sum of its day
 * rows (re-computed by the service on each PATCH).
 *
 * <p>Unique on {@code (timesheet_id, day_of_week)} so the upsert can match
 * on (parent, day) without juggling ids.</p>
 */
@Entity
@Table(
        name = "timesheet_days",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_timesheet_day",
                        columnNames = {"timesheet_id", "day_of_week"})
        },
        indexes = {
                @Index(name = "idx_timesheet_day_parent", columnList = "timesheet_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimesheetDay {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "timesheet_id", nullable = false)
    private Timesheet timesheet;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal hours = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
