package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Checklist item under a {@link Project}. Owned by the intern — they tick
 * items off as they work; the service can derive a progress % from the
 * done/total ratio, though the intern can also set progress_pct directly.
 */
@Entity
@Table(
        name = "project_tasks",
        indexes = {
                @Index(name = "idx_project_task_project_sort",
                        columnList = "project_id, sort_order")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTask {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    @Builder.Default
    private Boolean done = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
