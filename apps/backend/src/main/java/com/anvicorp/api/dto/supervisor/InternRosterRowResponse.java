package com.anvicorp.api.dto.supervisor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row in the assigned-interns roster. Carries current-week status only —
 * name, position, this week's material-ack / report-status / timesheet-status,
 * and the timestamp of last cycle activity. No compliance PII; deeper review
 * happens on the dedicated weekly-reports / timesheets pages via {@code reviewHref}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InternRosterRowResponse {
    private UUID candidateId;
    private UUID engagementId;
    private String internName;
    private String position;
    private String entityName;

    /** This week's Monday (server clock, UTC). */
    private LocalDate weekStart;

    /** True when this intern has acknowledged this week's released material. */
    private Boolean materialAcknowledged;

    /** This week's WeeklyReport status — DRAFT/SUBMITTED/RETURNED/APPROVED or null when no row exists yet. */
    private String reportStatus;

    /** This week's Timesheet status — DRAFT/SUBMITTED/APPROVED/REJECTED or null when no row exists yet. */
    private String timesheetStatus;

    /** Most-recent cycle activity for this intern (report submit / timesheet log / ack / etc.). */
    private Instant lastActivityAt;

    /** Deep-link to the supervisor's review surface for this intern (weekly-reports + intern_id). */
    private String reviewHref;
}
