package com.anvicorp.api.dto.report;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Intern → create a new weekly report. {@code weekStart} pairs the row with
 * the corresponding {@link com.anvicorp.api.entity.Timesheet#getWeekStart()}.
 * The four narrative fields are all optional at create time — interns can
 * compose progressively and save as DRAFT before sending.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWeeklyReportRequest {

    @NotNull
    private LocalDate weekStart;

    private String completedWork;
    private String blockers;
    private String learningOutcomes;
    private String nextPlan;
}
