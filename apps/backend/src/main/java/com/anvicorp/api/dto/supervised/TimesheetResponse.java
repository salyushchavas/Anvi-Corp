package com.anvicorp.api.dto.supervised;

import com.anvicorp.api.enums.TimesheetStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimesheetResponse {
    private UUID id;
    private LocalDate weekStart;
    private BigDecimal hours;
    private String description;
    private TimesheetStatus status;
    private String approvedByName;
    private Instant approvedAt;
    /** Rejection reason when status == REJECTED. */
    private String reviewNote;
    private Instant createdAt;
}
