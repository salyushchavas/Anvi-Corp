package com.anvicorp.api.dto.onboarding;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingSummaryResponse {
    private long totalTasks;
    private long completedTasks;
    private long pendingTasks;
    private long inProgressTasks;
    private long blockedTasks;
    /** Rounded percent in [0, 100]; denominator excludes NOT_APPLICABLE tasks. */
    private int progressPercent;
    /** Earliest still-actionable task with a dueDate, or null if none. */
    private OnboardingTaskResponse nextDueTask;
}
