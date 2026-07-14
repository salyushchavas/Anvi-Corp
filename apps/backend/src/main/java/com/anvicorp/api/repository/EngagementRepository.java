package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.enums.EngagementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3 step 1 — repository surface for {@link Engagement}. Nothing calls
 * this yet; future steps wire it into OFFER_ACCEPTED creation, the transition
 * guard, and the engagement-scoped queries that replace candidate-keyed reads
 * on Group C / I-9 / I-983 / onboarding.
 */
public interface EngagementRepository extends JpaRepository<Engagement, UUID> {

    Optional<Engagement> findByApplicationId(UUID applicationId);

    Optional<Engagement> findByOfferId(UUID offerId);

    /**
     * Phase-3 sweep — single engagement with the full {@code toResponse} graph
     * eagerly loaded (application → posting + candidate → user + entity +
     * supervisor + offer). Used by the detail endpoint so the DTO mapping
     * doesn't lazy-load after the @Transactional method closes under
     * {@code spring.jpa.open-in-view=false}.
     */
    @Query("SELECT e FROM Engagement e " +
            "JOIN FETCH e.candidate c " +
            "JOIN FETCH c.user u " +
            "JOIN FETCH e.entity en " +
            "LEFT JOIN FETCH e.application a " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH e.offer o " +
            "LEFT JOIN FETCH e.supervisor s " +
            "WHERE e.id = :id")
    Optional<Engagement> findByIdWithGraph(@Param("id") UUID id);

    List<Engagement> findByCandidateId(UUID candidateId);

    List<Engagement> findByCandidateIdAndStatus(UUID candidateId, EngagementStatus status);

    List<Engagement> findByStatus(EngagementStatus status);

    /**
     * Engagement-driven roster — every engagement in one of the given
     * statuses, newest-first, with the candidate + user + posting + entity
     * graph eager so the Evaluator's intern dropdown can render the DTO
     * without lazy access. Replaces the legacy {@code Application.status =
     * HIRED} filter (HIRED is deprecated on ApplicationStatus per Phase 3
     * step 10 — post-offer state lives on Engagement now).
     */
    @Query("SELECT e FROM Engagement e " +
            "JOIN FETCH e.candidate c " +
            "JOIN FETCH c.user u " +
            "JOIN FETCH e.entity en " +
            "LEFT JOIN FETCH e.application a " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "WHERE e.status IN :statuses " +
            "ORDER BY e.createdAt DESC")
    List<Engagement> findActiveRoster(
            @Param("statuses") Collection<EngagementStatus> statuses);

    boolean existsByApplicationId(UUID applicationId);

    // ── Phase 3 step 10 — count + roster helpers ────────────────────────────

    long countByStatus(EngagementStatus status);

    long countByStatusIn(Collection<EngagementStatus> statuses);

    /** Distinct candidate count across the given statuses. */
    @Query("SELECT COUNT(DISTINCT e.candidate.id) FROM Engagement e " +
            "WHERE e.status IN :statuses")
    long countDistinctCandidatesByStatusIn(@Param("statuses") Collection<EngagementStatus> statuses);

    /**
     * Supervised-interns roster: engagements that are ACTIVE or beyond, with
     * candidate + user + entity + assignedEvaluator + posting eagerly loaded.
     * Optional entity filter + case-insensitive search on candidate name/email.
     * Newest start first.
     */
    /**
     * Postgres 18 fix: the previous JPQL form
     *   {@code LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%'))}
     * tripped the new type-inference resolver — without an inferable type at
     * the parameter position, the {@code ||} concatenation defaulted to
     * {@code bytea} and {@code lower(bytea)} doesn't exist
     * (SQLSTATE 42883). We now precompute the {@code %lower%} wildcard
     * pattern in the service layer and bind it directly to {@code LIKE},
     * so the parameter type is unambiguously {@code text}. Caller passes
     * {@code null} to skip the filter.
     */
    @Query("SELECT e FROM Engagement e " +
            "JOIN FETCH e.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH c.assignedEvaluator ae " +
            "JOIN FETCH e.entity en " +
            "LEFT JOIN FETCH e.application a " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "WHERE e.status IN :statuses " +
            "AND (:entityId IS NULL OR en.id = :entityId) " +
            "AND (:searchPattern IS NULL " +
            "     OR LOWER(u.fullName) LIKE :searchPattern " +
            "     OR LOWER(u.email)    LIKE :searchPattern) " +
            "ORDER BY e.actualStartDate DESC NULLS LAST, e.createdAt DESC")
    List<Engagement> findRosterByStatusIn(@Param("statuses") Collection<EngagementStatus> statuses,
                                          @Param("entityId") UUID entityId,
                                          @Param("searchPattern") String searchPattern);

    /**
     * All engagements assigned to a given supervisor (User id), filtered by
     * status. Same fetch graph as {@link #findRosterByStatusIn}: candidate +
     * user + entity + application + job-posting all eagerly loaded so the
     * supervisor-dashboard mapper never trips a lazy proxy.
     *
     * <p>The supervisor-dashboard endpoint passes ACTIVE here; SUPER_ADMIN's
     * "see everything" path uses {@link #findRosterByStatusIn} with no supervisor
     * filter (we don't need a separate query for the bypass).
     */
    @Query("SELECT e FROM Engagement e " +
            "JOIN FETCH e.candidate c " +
            "JOIN FETCH c.user u " +
            "JOIN FETCH e.entity en " +
            "LEFT JOIN FETCH e.application a " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH e.supervisor s " +
            "WHERE e.supervisor.id = :supervisorUserId " +
            "AND e.status IN :statuses " +
            "ORDER BY e.actualStartDate DESC NULLS LAST, e.createdAt DESC")
    List<Engagement> findBySupervisorIdAndStatusIn(
            @Param("supervisorUserId") UUID supervisorUserId,
            @Param("statuses") Collection<EngagementStatus> statuses);
}
