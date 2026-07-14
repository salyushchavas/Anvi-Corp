package com.anvicorp.api.service;

import com.anvicorp.api.dto.admin.AdminOverviewResponse;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.enums.I983Status;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    /** Non-terminal E-Verify statuses considered "pending" for the dashboard. */
    private static final List<EVerifyStatus> EVERIFY_PENDING = List.of(
            EVerifyStatus.PENDING_SUBMISSION,
            EVerifyStatus.OPEN,
            EVerifyStatus.TENTATIVE_NONCONFIRMATION
    );

    /**
     * Phase 3 step 10 — engagement statuses that mean "this candidate has been
     * hired" (post-acceptance, in the employment lifecycle).
     */
    private static final List<EngagementStatus> HIRED_ENGAGEMENT_STATUSES = List.of(
            EngagementStatus.PENDING_COMPLIANCE,
            EngagementStatus.READY_TO_START,
            EngagementStatus.ACTIVE,
            EngagementStatus.COMPLETED
    );

    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final JobPostingRepository jobPostingRepository;
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final EVerifyCaseRepository everifyCaseRepository;
    private final EngagementRepository engagementRepository;

    @Transactional(readOnly = true)
    public AdminOverviewResponse build() {
        // Applications by status — emit a zero-filled bucket for every enum
        // value so the frontend can iterate without missing-key checks.
        Map<String, Long> applicationsByStatus = new LinkedHashMap<>();
        for (ApplicationStatus s : ApplicationStatus.values()) {
            applicationsByStatus.put(s.name(), applicationRepository.countByStatus(s));
        }

        // Phase 3 step 10 — totalHired + activeInterns now come from
        // Engagement.status. Legacy fallback (candidates with a HIRED
        // application but no engagement yet) is added on top so the dashboard
        // doesn't suddenly drop legacy hires until step-11 backfill catches up.
        long engagementHired = engagementRepository.countByStatusIn(HIRED_ENGAGEMENT_STATUSES);
        long engagementActive = engagementRepository.countByStatus(EngagementStatus.ACTIVE);
        @SuppressWarnings("deprecation")
        long legacyHired = applicationsByStatus.getOrDefault(ApplicationStatus.HIRED.name(), 0L);
        @SuppressWarnings("deprecation")
        long legacyActiveCandidates = applicationRepository
                .countDistinctCandidatesByStatus(ApplicationStatus.HIRED);
        // No exact overlap eliminator yet — step-11 backfill removes the
        // double-count by ensuring every legacy HIRED row has an engagement.
        // For now we expose the engagement-first count alongside legacy as
        // upper-bound estimates: that's accurate during the transition window.
        long totalHired = Math.max(engagementHired, legacyHired);
        long activeInterns = Math.max(engagementActive, legacyActiveCandidates);
        long openPostings = jobPostingRepository.findByStatus(JobPostingStatus.OPEN).size();
        long totalCandidates = candidateRepository.count();

        AdminOverviewResponse.ComplianceCounts compliance = AdminOverviewResponse.ComplianceCounts
                .builder()
                .i9Pending(i9FormRepository.countByStatusNot(I9Status.COMPLETED))
                .i983Pending(i983PlanRepository.countByStatusNot(I983Status.DSO_APPROVED))
                .everifyPending(everifyCaseRepository.countByStatusIn(EVERIFY_PENDING))
                .build();

        return AdminOverviewResponse.builder()
                .totalCandidates(totalCandidates)
                .totalHired(totalHired)
                .activeInterns(activeInterns)
                .openPostings(openPostings)
                .applicationsByStatus(applicationsByStatus)
                .compliance(compliance)
                .build();
    }
}
