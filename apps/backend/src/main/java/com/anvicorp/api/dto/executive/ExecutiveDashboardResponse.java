package com.anvicorp.api.dto.executive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Read-only aggregate payload for the Executive (leadership) dashboard.
 *
 * <h2>Privilege boundary</h2>
 * Aggregate counts + rates only. NO individual PII (no name lists tied to
 * compliance state, no SSN/A-Number/document numbers, no work-auth detail).
 * Supervisor-load surfaces names because supervisors are staff identities,
 * not regulated PII.
 *
 * <h2>Mutability</h2>
 * The endpoint that serves this payload has no companion write endpoints.
 * The frontend renders it without any action buttons — strictly oversight.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutiveDashboardResponse {
    /** Caller's display name for the greeting. */
    private String operatorName;

    /** True when the caller holds SUPER_ADMIN and is viewing leadership metrics. */
    private boolean isSuperAdminView;

    private HiringFunnelResponse hiringFunnel;
    private InternProgramResponse internProgram;
    private ComplianceHealthResponse complianceHealth;
    private WeeklyCycleHealthResponse weeklyCycle;
    private List<SupervisorLoadRowResponse> supervisorLoad;
}
