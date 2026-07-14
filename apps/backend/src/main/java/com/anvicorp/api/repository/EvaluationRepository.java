package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Evaluation;
import com.anvicorp.api.enums.EvaluationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvaluationRepository extends JpaRepository<Evaluation, UUID> {

    /**
     * Single evaluation with intern + user + engagement + evaluator fetched
     * eagerly so DTO mapping doesn't lazy-load after the @Transactional
     * service method closes.
     */
    @Query("SELECT e FROM Evaluation e " +
            "JOIN FETCH e.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH e.engagement eng " +
            "LEFT JOIN FETCH eng.supervisor sv " +
            "JOIN FETCH e.evaluator ev " +
            "WHERE e.id = :id")
    Optional<Evaluation> findByIdWithGraph(@Param("id") UUID id);

    /** All evaluations for a given intern (candidate id), newest first. */
    @Query("SELECT e FROM Evaluation e " +
            "JOIN FETCH e.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH e.engagement eng " +
            "JOIN FETCH e.evaluator ev " +
            "WHERE i.id = :internId " +
            "ORDER BY e.createdAt DESC")
    List<Evaluation> findByInternIdWithGraph(@Param("internId") UUID internId);

    /**
     * Authored-by view — used by the supervisor's evaluations board + the
     * supervisor dashboard action queue (count of DRAFT). Newest first.
     */
    @Query("SELECT e FROM Evaluation e " +
            "JOIN FETCH e.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH e.engagement eng " +
            "JOIN FETCH e.evaluator ev " +
            "WHERE ev.id = :evaluatorUserId " +
            "ORDER BY e.createdAt DESC")
    List<Evaluation> findByEvaluatorIdWithGraph(@Param("evaluatorUserId") UUID evaluatorUserId);

    long countByEvaluatorIdAndStatus(UUID evaluatorId, EvaluationStatus status);

    /** Used by the intern's /me read — only FINALIZED rows surface. */
    @Query("SELECT e FROM Evaluation e " +
            "JOIN FETCH e.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH e.engagement eng " +
            "JOIN FETCH e.evaluator ev " +
            "WHERE i.user.id = :userId AND e.status = :status " +
            "ORDER BY e.createdAt DESC")
    List<Evaluation> findFinalizedByCandidateUserIdWithGraph(
            @Param("userId") UUID userId,
            @Param("status") EvaluationStatus status);

    /**
     * Intern self-review surface — DRAFT evaluations the intern owns, filtered
     * to the self-review-eligible types (I-983 only). The intern needs to see
     * these draft rows BEFORE the supervisor finalizes, so the regular /me
     * (FINALIZED only) won't surface them.
     */
    @Query("SELECT e FROM Evaluation e " +
            "JOIN FETCH e.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH e.engagement eng " +
            "JOIN FETCH e.evaluator ev " +
            "WHERE i.user.id = :userId " +
            "  AND e.status = com.anvicorp.api.enums.EvaluationStatus.DRAFT " +
            "  AND e.type IN (com.anvicorp.api.enums.EvaluationType.I983_12MO, " +
            "                 com.anvicorp.api.enums.EvaluationType.I983_FINAL) " +
            "ORDER BY e.createdAt DESC")
    List<Evaluation> findSelfReviewableDraftsByCandidateUserIdWithGraph(
            @Param("userId") UUID userId);

    long countByStatus(EvaluationStatus status);

    /** Average overall rating across FINALIZED evaluations. Null when none. */
    @Query("SELECT AVG(e.overallRating) FROM Evaluation e " +
            "WHERE e.status = com.anvicorp.api.enums.EvaluationStatus.FINALIZED " +
            "  AND e.overallRating IS NOT NULL")
    Double averageFinalizedOverallRating();

    /**
     * DRAFT evaluations whose createdAt is older than {@code cutoff} — the
     * scheduler reads this for the "evaluation pending finalization" reminder
     * to the supervisor.
     */
    @Query("SELECT e FROM Evaluation e " +
            "JOIN FETCH e.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH e.engagement eng " +
            "JOIN FETCH e.evaluator ev " +
            "WHERE e.status = com.anvicorp.api.enums.EvaluationStatus.DRAFT " +
            "  AND e.createdAt < :cutoff " +
            "ORDER BY e.createdAt ASC")
    List<Evaluation> findDraftsOlderThanWithGraph(@Param("cutoff") java.time.Instant cutoff);

    /**
     * All DRAFT I-983 self-review-eligible rows — used by the daily scheduler
     * to nudge interns who still owe a reflection.
     */
    @Query("SELECT e FROM Evaluation e " +
            "JOIN FETCH e.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH e.engagement eng " +
            "JOIN FETCH e.evaluator ev " +
            "WHERE e.status = com.anvicorp.api.enums.EvaluationStatus.DRAFT " +
            "  AND e.type IN (com.anvicorp.api.enums.EvaluationType.I983_12MO, " +
            "                 com.anvicorp.api.enums.EvaluationType.I983_FINAL) " +
            "ORDER BY e.createdAt ASC")
    List<Evaluation> findAllSelfReviewableDraftsWithGraph();
}
