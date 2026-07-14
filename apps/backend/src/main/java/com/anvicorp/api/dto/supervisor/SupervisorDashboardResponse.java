package com.anvicorp.api.dto.supervisor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Aggregate payload for the Technical Evaluator dashboard. Single read,
 * scoped server-side to engagements the caller supervises (SUPER_ADMIN
 * bypasses the scope and sees all).
 *
 * <h2>Privilege boundary</h2>
 * Carries no compliance PII (no SSN / A-Number / DOB / document numbers / I-9
 * detail / E-Verify state — the service never touches those repos). Carries
 * no user-management / audit-export data. The {@code lastActivity} text comes
 * from the same pre-rendered summary the HR dashboard uses — no raw audit
 * before/after JSON.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupervisorDashboardResponse {

    private String supervisorName;

    /** True when the caller holds SUPER_ADMIN and is seeing every active engagement. */
    private boolean isSuperAdminView;

    /** Action items waiting on this supervisor. Each item: label + count + deep-link. */
    private List<SupervisorActionItemResponse> needsAttention;

    /** One row per ACTIVE engagement the supervisor owns. */
    private List<InternRosterRowResponse> internRoster;

    /** Upcoming evaluation checkpoints + report / timesheet deadlines for this supervisor's interns. */
    private List<SupervisorUpcomingItemResponse> upcoming;

    /** Recent activity scoped to this supervisor's interns. Read-only feed; no export. */
    private List<SupervisorActivityItemResponse> recentActivity;
}
