package com.anvicorp.api.dto.rm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.dto.qa.QaSessionResponse;
import com.anvicorp.api.dto.supervised.TimesheetWeekResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportingManagerDashboardResponse(
        long pendingQaCount,
        long qaInProgressCount,
        long pendingTimesheetCount,
        long completedThisMonthCount,
        List<ProjectAwaitingQa> projectsAwaitingQa,
        List<QaSessionResponse> qaInProgress,
        List<TimesheetWeekResponse> pendingTimesheets
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectAwaitingQa(
            UUID projectId,
            String projectTitle,
            UUID internUserId,
            String internName,
            Instant techApprovedAt
    ) {}
}
