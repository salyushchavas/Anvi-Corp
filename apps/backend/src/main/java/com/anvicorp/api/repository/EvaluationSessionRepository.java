package com.anvicorp.api.repository;

import com.anvicorp.api.entity.EvaluationSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvaluationSessionRepository extends JpaRepository<EvaluationSession, UUID> {

    /**
     * Staff list for an intern, newest scheduled first. Fetch-joins intern.user
     * and the (nullable) evaluator so the DTO mapper renders evaluatorName
     * without hitting a detached lazy proxy after the transaction closes.
     */
    @Query("SELECT s FROM EvaluationSession s " +
            "JOIN FETCH s.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH s.evaluator e " +
            "WHERE i.id = :candidateId " +
            "ORDER BY s.scheduledAt DESC")
    List<EvaluationSession> findForIntern(@Param("candidateId") UUID candidateId);

    /** Candidate's own sessions, newest scheduled first. */
    @Query("SELECT s FROM EvaluationSession s " +
            "JOIN FETCH s.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH s.evaluator e " +
            "WHERE iu.id = :userId " +
            "ORDER BY s.scheduledAt DESC")
    List<EvaluationSession> findForCandidateUser(@Param("userId") UUID userId);

    /**
     * Single session with intern + intern.user + evaluator eagerly loaded so
     * mutation handlers (complete/miss) can write the response DTO without
     * tripping a LazyInitializationException.
     */
    @Query("SELECT s FROM EvaluationSession s " +
            "JOIN FETCH s.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH s.evaluator e " +
            "WHERE s.id = :id")
    Optional<EvaluationSession> findByIdWithGraph(@Param("id") UUID id);

    /**
     * Soonest SCHEDULED session for an intern with {@code scheduledAt >= :from}.
     * Pass {@code Pageable.ofSize(1)} from the caller. Evaluator is left-fetched
     * so the DTO mapper can read the name without a lazy hit.
     */
    @Query("SELECT s FROM EvaluationSession s " +
            "LEFT JOIN FETCH s.evaluator e " +
            "WHERE s.intern.user.id = :userId " +
            "AND s.status = com.anvicorp.api.enums.EvaluationSessionStatus.SCHEDULED " +
            "AND s.scheduledAt >= :from " +
            "ORDER BY s.scheduledAt ASC")
    List<EvaluationSession> findUpcomingForCandidateUser(@Param("userId") UUID userId,
                                                        @Param("from") LocalDateTime from,
                                                        Pageable pageable);

    /**
     * Most recent COMPLETED session for an intern by completedAt. Caller passes
     * {@code Pageable.ofSize(1)}.
     */
    @Query("SELECT s FROM EvaluationSession s " +
            "LEFT JOIN FETCH s.evaluator e " +
            "WHERE s.intern.user.id = :userId " +
            "AND s.status = com.anvicorp.api.enums.EvaluationSessionStatus.COMPLETED " +
            "ORDER BY s.completedAt DESC")
    List<EvaluationSession> findLatestCompletedForCandidateUser(@Param("userId") UUID userId,
                                                                Pageable pageable);

    /**
     * Every session whose {@code evaluator} is the given user. Fetch-joins
     * intern + intern.user so the Evaluator-area list mappers can render
     * candidate names without lazy-loading.
     */
    @Query("SELECT s FROM EvaluationSession s " +
            "JOIN FETCH s.intern i " +
            "JOIN FETCH i.user u " +
            "LEFT JOIN FETCH s.evaluator e " +
            "WHERE e.id = :evaluatorUserId " +
            "ORDER BY s.scheduledAt DESC")
    List<EvaluationSession> findForEvaluatorUser(@Param("evaluatorUserId") UUID evaluatorUserId);

    // ── Phase 3 step 8 — engagement-scoped queries (alongside intern_id ones) ──

    /**
     * All sessions for a given engagement, newest scheduled first. Same
     * fetch graph as {@link #findForIntern(UUID)}; legacy rows with null
     * engagement_id stay reachable via the intern-keyed query.
     */
    @Query("SELECT s FROM EvaluationSession s " +
            "JOIN FETCH s.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH s.evaluator e " +
            "WHERE s.engagement.id = :engagementId " +
            "ORDER BY s.scheduledAt DESC")
    List<EvaluationSession> findForEngagement(@Param("engagementId") UUID engagementId);
}
