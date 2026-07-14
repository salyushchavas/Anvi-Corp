package com.anvicorp.api.dto.supervised;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wraps the timesheet list with the running total of approved hours so the
 * intern and the staff views can render the "Total approved: N hrs" header
 * without a second round-trip.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimesheetListResponse {
    private List<TimesheetResponse> entries;
    private BigDecimal totalApprovedHours;
}
