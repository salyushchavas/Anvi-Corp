package com.anvicorp.api.dto.report;

import com.anvicorp.api.enums.WeeklyReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Single weekly report row. Shared between intern (own view) and supervisor
 * (intern's report list + review panel). No PII beyond {@code internName}
 * (the candidate's display name).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyReportResponse {
    private UUID id;
    private UUID internCandidateId;
    private String internName;
    private LocalDate weekStart;
    private String completedWork;
    private String blockers;
    private String learningOutcomes;
    private String nextPlan;
    private WeeklyReportStatus status;
    private Instant submittedAt;
    private UUID reviewedById;
    private String reviewedByName;
    private String reviewNotes;
    private Instant reviewedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
