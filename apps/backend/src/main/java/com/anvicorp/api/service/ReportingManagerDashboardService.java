package com.anvicorp.api.service;

import com.anvicorp.api.dto.qa.QaSessionResponse;
import com.anvicorp.api.dto.rm.ReportingManagerDashboardResponse;
import com.anvicorp.api.dto.rm.ReportingManagerDashboardResponse.ProjectAwaitingQa;
import com.anvicorp.api.dto.supervised.TimesheetWeekResponse;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.QaSession;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.QaSessionStatus;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.QaSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Composes the Reporting Manager dashboard view from existing repositories.
 * No new persistence — every figure is read live.
 */
@Service
@RequiredArgsConstructor
public class ReportingManagerDashboardService {

    private final ProjectRepository projectRepository;
    private final QaSessionRepository qaSessionRepository;
    private final QaSessionService qaSessionService;
    private final TimesheetService timesheetService;

    @Transactional(readOnly = true)
    public ReportingManagerDashboardResponse build(User caller) {
        // Role-based scope — any REPORTING_MANAGER sees every RM-eligible
        // project / session / timesheet across all engagements. The
        // per-engagement RM FK is no longer the filter.
        long pendingQa = projectRepository.countByStatus(ProjectStatus.TECH_APPROVED);
        long inProgress = projectRepository.countByStatus(ProjectStatus.PENDING_VIVA);

        Instant monthStart = LocalDate.now()
                .withDayOfMonth(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        long completedThisMonth = projectRepository.countCompletedSince(monthStart);

        List<Project> awaiting = projectRepository.findAllByStatusInWithGraph(
                List.of(ProjectStatus.TECH_APPROVED));
        List<ProjectAwaitingQa> awaitingDtos = new ArrayList<>();
        for (Project p : awaiting) {
            var intern = p.getIntern();
            var internUser = intern != null ? intern.getUser() : null;
            awaitingDtos.add(new ProjectAwaitingQa(
                    p.getId(),
                    p.getTitle(),
                    internUser != null ? internUser.getId() : null,
                    internUser != null ? internUser.getFullName() : null,
                    p.getReviewedAt()
            ));
        }

        List<QaSession> activeSessions = qaSessionRepository.findAllByStatusInWithGraph(
                List.of(QaSessionStatus.SCHEDULED, QaSessionStatus.CONDUCTED));
        List<QaSessionResponse> activeSessionDtos = activeSessions.stream()
                .map(qaSessionService::toResponse)
                .toList();

        // Top 5 pending timesheets (oldest first by weekStart asc). The full
        // queue page calls /api/v1/timesheets/pending-approval directly.
        // Single fetch — derive both the slice and the total count from one
        // call. Calling listPendingApproval twice scans + maps every
        // submitted row twice on every dashboard load.
        List<TimesheetWeekResponse> allPending = timesheetService.listPendingApproval(caller);
        long pendingTimesheetCount = allPending.size();
        List<TimesheetWeekResponse> pendingTimesheets = allPending.stream()
                .sorted((a, b) -> {
                    if (a.weekStart() == null && b.weekStart() == null) return 0;
                    if (a.weekStart() == null) return 1;
                    if (b.weekStart() == null) return -1;
                    return a.weekStart().compareTo(b.weekStart());
                })
                .limit(5)
                .toList();

        return new ReportingManagerDashboardResponse(
                pendingQa,
                inProgress,
                pendingTimesheetCount,
                completedThisMonth,
                awaitingDtos,
                activeSessionDtos,
                pendingTimesheets
        );
    }
}
