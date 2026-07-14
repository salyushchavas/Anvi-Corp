package com.anvicorp.api.dto.evaluation;

import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only context block the evaluation form shows alongside the rubric —
 * the intern's COMPLETED projects + weekly-report stats over the eval
 * period. Helps the supervisor write a grounded assessment.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationContextResponse {
    private List<CompletedProjectMini> completedProjects;
    private ReportStats reportStats;
    private TimesheetStats timesheetStats;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CompletedProjectMini {
        private UUID id;
        private String title;
        private LocalDate completedDate;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReportStats {
        private long totalCount;
        private long approvedCount;
        private long returnedCount;
        private long pendingCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimesheetStats {
        private long totalCount;
        private long approvedCount;
        private String approvedHours;
    }
}
