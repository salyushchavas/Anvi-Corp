package com.anvicorp.api.service;

import com.anvicorp.api.dto.supervised.SupervisedOverviewResponse;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EvaluationSession;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EvaluationSessionRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.WorkAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the at-a-glance summary for the intern's My Work overview header.
 *
 * Hard rules:
 *  - Read-only transaction wraps the whole method so lazy associations the
 *    DTO mapper touches (Candidate.assignedEvaluator, EvaluationSession.evaluator)
 *    stay attached. Repository queries also fetch-join those associations as a
 *    belt-and-braces defense.
 *  - Empty/missing-data path is a normal 200: a CANDIDATE without a Candidate
 *    row, or one with zero records in any sub-domain, gets a fully-zeroed/null
 *    response — never a 404 or NPE.
 *  - Ownership is derived strictly from the authenticated User; no client-
 *    supplied id reaches the query layer.
 */
@Service
@RequiredArgsConstructor
public class SupervisedOverviewService {

    private final CandidateRepository candidateRepository;
    private final TimesheetRepository timesheetRepository;
    private final WorkAssignmentRepository workAssignmentRepository;
    private final EvaluationSessionRepository evaluationSessionRepository;

    @Transactional(readOnly = true)
    public SupervisedOverviewResponse forUser(User caller) {
        // Defensive: a caller with no authentication context still gets a clean
        // empty response shape instead of crashing. The PreAuthorize on the
        // controller should already block this, but the service stays paranoid.
        if (caller == null || caller.getId() == null) {
            return empty();
        }
        UUID userId = caller.getId();

        // Candidate row — may not exist if the user just registered but no
        // application/resume path has lazy-created one yet. Treat absence as
        // a fully-zeroed overview rather than a 404.
        Optional<Candidate> candidateOpt = candidateRepository.findByUserIdWithEvaluator(userId);
        String evaluatorName = candidateOpt
                .map(Candidate::getAssignedEvaluator)
                .map(User::getFullName)
                .orElse(null);

        BigDecimal totalApprovedHours = timesheetRepository.sumApprovedHoursForCandidateUser(userId);
        if (totalApprovedHours == null) {
            // COALESCE(SUM, 0) in the query already guards against null, but
            // belt-and-braces in case a future query change drops it.
            totalApprovedHours = BigDecimal.ZERO;
        }

        long openAssignments = workAssignmentRepository.countOpenForCandidateUser(userId);
        long reviewedAssignments = workAssignmentRepository.countReviewedForCandidateUser(userId);

        SupervisedOverviewResponse.NextEvaluation next = null;
        List<EvaluationSession> upcoming = evaluationSessionRepository
                .findUpcomingForCandidateUser(userId, LocalDateTime.now(), PageRequest.of(0, 1));
        if (upcoming != null && !upcoming.isEmpty()) {
            EvaluationSession s = upcoming.get(0);
            next = SupervisedOverviewResponse.NextEvaluation.builder()
                    .scheduledAt(s.getScheduledAt())
                    .evaluatorName(s.getEvaluator() != null ? s.getEvaluator().getFullName() : null)
                    .build();
        }

        SupervisedOverviewResponse.LatestEvaluation latest = null;
        List<EvaluationSession> completed = evaluationSessionRepository
                .findLatestCompletedForCandidateUser(userId, PageRequest.of(0, 1));
        if (completed != null && !completed.isEmpty()) {
            EvaluationSession s = completed.get(0);
            latest = SupervisedOverviewResponse.LatestEvaluation.builder()
                    .overallRating(s.getOverallRating())
                    .completedAt(s.getCompletedAt())
                    .build();
        }

        return SupervisedOverviewResponse.builder()
                .totalApprovedHours(totalApprovedHours)
                .openAssignments(openAssignments)
                .reviewedAssignments(reviewedAssignments)
                .nextEvaluation(next)
                .latestEvaluation(latest)
                .evaluatorName(evaluatorName)
                .build();
    }

    private SupervisedOverviewResponse empty() {
        return SupervisedOverviewResponse.builder()
                .totalApprovedHours(BigDecimal.ZERO)
                .openAssignments(0)
                .reviewedAssignments(0)
                .nextEvaluation(null)
                .latestEvaluation(null)
                .evaluatorName(null)
                .build();
    }
}
