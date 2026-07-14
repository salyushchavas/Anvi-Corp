package com.anvicorp.api.dto.supervisor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row in the supervisor's upcoming-events list — evaluation checkpoints,
 * report / timesheet deadlines. Pre-sorted soonest-first by the service.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupervisorUpcomingItemResponse {
    /** EVALUATION | REPORT_DEADLINE | TIMESHEET_DEADLINE */
    private String type;
    private String title;
    private String subtitle;
    private Instant at;
    private String href;
}
