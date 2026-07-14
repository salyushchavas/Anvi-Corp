package com.anvicorp.api.service;

import com.anvicorp.api.dto.supervised.EvaluatorAssignmentResponse;
import com.anvicorp.api.dto.supervised.EvaluatorDashboardResponse;
import com.anvicorp.api.dto.supervised.EvaluatorInternResponse;
import com.anvicorp.api.dto.supervised.EvaluatorSessionResponse;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.EvaluationSession;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WorkAssignment;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.EvaluationSessionStatus;
import com.anvicorp.api.enums.WorkAssignmentStatus;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.EvaluationSessionRepository;
import com.anvicorp.api.repository.WorkAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only aggregates for the Evaluator area. Backed by the existing Group C
 * repositories — all queries fetch-join the intern/user/evaluator graphs so
 * the DTO mappers never touch a lazy proxy after the transaction closes.
 *
 * Every method wraps in {@code @Transactional(readOnly = true)} as belt-and-
 * braces against accidental lazy access from the controller layer.
 */
@Service
@RequiredArgsConstructor
public class EvaluatorListsService {

    private static final int DASHBOARD_TOP_N = 5;

    private final ApplicationRepository applicationRepository;
    private final EvaluationSessionRepository evaluationSessionRepository;
    private final WorkAssignmentRepository workAssignmentRepository;
    private final EngagementRepository engagementRepository;

    private static final List<EngagementStatus> ROSTER_STATUSES = List.of(
            EngagementStatus.READY_TO_START,
            EngagementStatus.ACTIVE);

    @Transactional(readOnly = true)
    public List<EvaluatorInternResponse> listInterns(User evaluator) {
        if (evaluator == null) return List.of();
        // Engagement-driven roster — post-Phase-3, Engagement.status is the
        // source of truth for who's hired (Application.status = HIRED is
        // deprecated). Returns every intern whose engagement is in
        // READY_TO_START or ACTIVE, role-scoped via the controller's
        // @PreAuthorize (any TECHNICAL_EVALUATOR can see the full roster).
        return dedupeFromEngagements(
                engagementRepository.findActiveRoster(ROSTER_STATUSES));
    }

    @Transactional(readOnly = true)
    public List<EvaluatorSessionResponse> listSessions(User evaluator) {
        if (evaluator == null) return List.of();
        return evaluationSessionRepository.findForEvaluatorUser(evaluator.getId())
                .stream().map(this::toSession).toList();
    }

    @Transactional(readOnly = true)
    public List<EvaluatorAssignmentResponse> listAssignments(User evaluator,
                                                             WorkAssignmentStatus statusFilter) {
        if (evaluator == null) return List.of();
        return workAssignmentRepository
                .findForEvaluatorAssignedInterns(evaluator.getId(), statusFilter)
                .stream().map(this::toAssignment).toList();
    }

    @Transactional(readOnly = true)
    public EvaluatorDashboardResponse dashboard(User evaluator) {
        if (evaluator == null) {
            return emptyDashboard();
        }
        UUID evalId = evaluator.getId();

        List<EvaluatorInternResponse> interns = dedupeFromEngagements(
                engagementRepository.findActiveRoster(ROSTER_STATUSES));

        List<EvaluationSession> allSessions =
                evaluationSessionRepository.findForEvaluatorUser(evalId);
        LocalDateTime now = LocalDateTime.now();
        List<EvaluatorSessionResponse> upcoming = allSessions.stream()
                .filter(s -> s.getStatus() == EvaluationSessionStatus.SCHEDULED
                        && s.getScheduledAt() != null
                        && !s.getScheduledAt().isBefore(now))
                .sorted((a, b) -> a.getScheduledAt().compareTo(b.getScheduledAt()))
                .limit(DASHBOARD_TOP_N)
                .map(this::toSession)
                .toList();

        BigDecimal averageRating = computeAverageRating(allSessions);

        List<EvaluatorAssignmentResponse> pendingReviews = workAssignmentRepository
                .findForEvaluatorAssignedInterns(evalId, WorkAssignmentStatus.SUBMITTED)
                .stream()
                .limit(DASHBOARD_TOP_N)
                .map(this::toAssignment)
                .toList();

        long upcomingCount = allSessions.stream()
                .filter(s -> s.getStatus() == EvaluationSessionStatus.SCHEDULED
                        && s.getScheduledAt() != null
                        && !s.getScheduledAt().isBefore(now))
                .count();
        long pendingReviewsCount = workAssignmentRepository
                .findForEvaluatorAssignedInterns(evalId, WorkAssignmentStatus.SUBMITTED)
                .size();

        return EvaluatorDashboardResponse.builder()
                .internsCount(interns.size())
                .upcomingSessionsCount(upcomingCount)
                .pendingReviewsCount(pendingReviewsCount)
                .averageRating(averageRating)
                .myInterns(interns.stream().limit(DASHBOARD_TOP_N).toList())
                .upcomingSessions(upcoming)
                .pendingReviews(pendingReviews)
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Convert HIRED applications -> EvaluatorInternResponse, dedupe by candidate
     * id (an intern with multiple HIRED applications keeps the most recent —
     * the query already orders DESC by statusUpdatedAt).
     */
    private List<EvaluatorInternResponse> dedupeByCandidate(List<Application> hiredApps) {
        Set<UUID> seen = new HashSet<>();
        List<EvaluatorInternResponse> out = new ArrayList<>(hiredApps.size());
        for (Application app : hiredApps) {
            Candidate c = app.getCandidate();
            if (c == null || !seen.add(c.getId())) continue;
            User u = c.getUser();
            JobPosting jp = app.getJobPosting();
            StaffingEntity e = jp != null ? jp.getEntity() : null;
            out.add(EvaluatorInternResponse.builder()
                    .candidateId(c.getId())
                    .name(u != null ? u.getFullName() : null)
                    .position(jp != null ? jp.getTitle() : null)
                    .entityName(e != null ? e.getName() : null)
                    .build());
        }
        return out;
    }

    /**
     * Map active engagements to the intern DTO, deduping by candidate id
     * (an intern with multiple engagements keeps the first — repo orders
     * newest first).
     */
    private List<EvaluatorInternResponse> dedupeFromEngagements(List<Engagement> engagements) {
        Set<UUID> seen = new HashSet<>();
        List<EvaluatorInternResponse> out = new ArrayList<>(engagements.size());
        for (Engagement eng : engagements) {
            Candidate c = eng.getCandidate();
            if (c == null || !seen.add(c.getId())) continue;
            User u = c.getUser();
            Application app = eng.getApplication();
            JobPosting jp = app != null ? app.getJobPosting() : null;
            StaffingEntity e = eng.getEntity();
            out.add(EvaluatorInternResponse.builder()
                    .candidateId(c.getId())
                    .name(u != null ? u.getFullName() : null)
                    .position(jp != null ? jp.getTitle() : null)
                    .entityName(e != null ? e.getName() : null)
                    .build());
        }
        return out;
    }

    private EvaluatorSessionResponse toSession(EvaluationSession s) {
        Candidate intern = s.getIntern();
        User u = intern != null ? intern.getUser() : null;
        return EvaluatorSessionResponse.builder()
                .sessionId(s.getId())
                .candidateId(intern != null ? intern.getId() : null)
                .internName(u != null ? u.getFullName() : null)
                .scheduledAt(s.getScheduledAt())
                .status(s.getStatus())
                .overallRating(s.getOverallRating())
                .completedAt(s.getCompletedAt())
                .build();
    }

    private EvaluatorAssignmentResponse toAssignment(WorkAssignment wa) {
        Candidate intern = wa.getIntern();
        User u = intern != null ? intern.getUser() : null;
        return EvaluatorAssignmentResponse.builder()
                .assignmentId(wa.getId())
                .candidateId(intern != null ? intern.getId() : null)
                .internName(u != null ? u.getFullName() : null)
                .title(wa.getTitle())
                .status(wa.getStatus())
                .dueDate(wa.getDueDate())
                .submittedAt(wa.getSubmittedAt())
                .build();
    }

    private BigDecimal computeAverageRating(List<EvaluationSession> sessions) {
        List<Integer> ratings = sessions.stream()
                .filter(s -> s.getStatus() == EvaluationSessionStatus.COMPLETED)
                .map(EvaluationSession::getOverallRating)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ratings.isEmpty()) return null;
        long sum = 0;
        for (Integer r : ratings) sum += r;
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(ratings.size()), 2, RoundingMode.HALF_UP);
    }

    private EvaluatorDashboardResponse emptyDashboard() {
        return EvaluatorDashboardResponse.builder()
                .internsCount(0)
                .upcomingSessionsCount(0)
                .pendingReviewsCount(0)
                .averageRating(null)
                .myInterns(List.of())
                .upcomingSessions(List.of())
                .pendingReviews(List.of())
                .build();
    }
}
