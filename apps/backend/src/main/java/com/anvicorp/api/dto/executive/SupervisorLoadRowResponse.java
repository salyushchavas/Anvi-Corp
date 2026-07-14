package com.anvicorp.api.dto.executive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One supervisor's roster + pending-review backlog. Surfaces staff names
 * (supervisors are program identities, not regulated PII). Used by
 * leadership to spot under- or over-loaded supervisors.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupervisorLoadRowResponse {
    private UUID supervisorUserId;
    private String supervisorName;
    private long activeInterns;
    private long pendingReports;
    private long pendingTimesheets;
}
