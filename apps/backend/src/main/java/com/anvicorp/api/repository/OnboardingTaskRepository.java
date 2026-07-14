package com.anvicorp.api.repository;

import com.anvicorp.api.entity.OnboardingTask;
import com.anvicorp.api.enums.OnboardingTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OnboardingTaskRepository extends JpaRepository<OnboardingTask, UUID> {

    List<OnboardingTask> findByCandidateIdOrderBySortOrderAsc(UUID candidateId);

    List<OnboardingTask> findByCandidateIdAndOfferIdOrderBySortOrderAsc(
            UUID candidateId, UUID offerId);

    Optional<OnboardingTask> findByCandidateIdAndTaskKeyAndOfferId(
            UUID candidateId, String taskKey, UUID offerId);

    boolean existsByCandidateIdAndOfferId(UUID candidateId, UUID offerId);

    long countByCandidateIdAndStatus(UUID candidateId, OnboardingTaskStatus status);

    // ── Phase 3 step 8 — engagement-scoped queries (alongside candidate-keyed) ──

    List<OnboardingTask> findByEngagementIdOrderBySortOrderAsc(UUID engagementId);

    boolean existsByEngagementId(UUID engagementId);

    /**
     * Overdue tasks for the compliance-reminder scheduler. Returns rows that
     * are still PENDING / IN_PROGRESS, have a {@code dueDate} on file, and
     * crossed past their due date by at least {@code overdueDaysCutoff}.
     * Joins candidate→user so the scheduler doesn't lazy-load per row.
     */
    @Query("SELECT t FROM OnboardingTask t " +
            "JOIN FETCH t.candidate c " +
            "JOIN FETCH c.user u " +
            "WHERE t.status IN (com.anvicorp.api.enums.OnboardingTaskStatus.PENDING, " +
            "                   com.anvicorp.api.enums.OnboardingTaskStatus.IN_PROGRESS) " +
            "  AND t.dueDate IS NOT NULL " +
            "  AND t.dueDate <= :cutoff " +
            "ORDER BY t.dueDate ASC")
    List<OnboardingTask> findOverdueWithGraph(@Param("cutoff") LocalDate cutoff);
}
