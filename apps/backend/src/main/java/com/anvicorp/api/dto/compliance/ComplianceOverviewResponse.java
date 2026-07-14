package com.anvicorp.api.dto.compliance;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceOverviewResponse {
    private ComplianceStats stats;
    private List<ComplianceAlert> alerts;
    private List<UpcomingDeadline> upcomingDeadlines;
    private List<RecentAction> recentActions;
}
