package com.anvicorp.api.dto.project;

import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * Intern → update progress on a project they own. Either {@code progressPct}
 * (0–100, clamped) and/or {@code taskUpdates} (per-task done flag flips)
 * can be supplied. Status stays IN_PROGRESS regardless.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateProgressRequest {

    /** 0–100. Clamped server-side. Null leaves the existing value. */
    private Integer progressPct;

    /** Per-task done updates. */
    private List<TaskUpdate> taskUpdates;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TaskUpdate {
        private UUID taskId;
        private Boolean done;
    }
}
