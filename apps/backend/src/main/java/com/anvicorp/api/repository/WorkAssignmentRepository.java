package com.anvicorp.api.repository;

import com.anvicorp.api.entity.WorkAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkAssignmentRepository extends JpaRepository<WorkAssignment, UUID> {

    /**
     * Staff list of an intern's assignments. Fetch-joins intern.user (for the
     * future case of needing intern name) and assignedBy so the DTO mapper can
     * read fullName without re-opening a session. Newest first.
     */
    @Query("SELECT wa FROM WorkAssignment wa " +
            "JOIN FETCH wa.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH wa.assignedBy ab " +
            "WHERE i.id = :candidateId " +
            "ORDER BY wa.createdAt DESC")
    List<WorkAssignment> findForIntern(@Param("candidateId") UUID candidateId);

    /**
     * Candidate's own assignments. Same fetch graph as the staff list so the
     * DTO mapper can populate assignedByName.
     */
    @Query("SELECT wa FROM WorkAssignment wa " +
            "JOIN FETCH wa.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH wa.assignedBy ab " +
            "WHERE iu.id = :userId " +
            "ORDER BY wa.createdAt DESC")
    List<WorkAssignment> findForCandidateUser(@Param("userId") UUID userId);

    /**
     * Single assignment with intern + intern.user eagerly loaded so the
     * ownership check ({@code wa.intern.user.id == caller.id}) doesn't trip
     * a LazyInitializationException after the transaction closes.
     */
    @Query("SELECT wa FROM WorkAssignment wa " +
            "JOIN FETCH wa.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH wa.assignedBy ab " +
            "WHERE wa.id = :id")
    Optional<WorkAssignment> findByIdWithGraph(@Param("id") UUID id);

    /** Count of an intern's assignments that have NOT been REVIEWED yet. */
    @Query("SELECT COUNT(wa) FROM WorkAssignment wa " +
            "WHERE wa.intern.user.id = :userId " +
            "AND wa.status <> com.anvicorp.api.enums.WorkAssignmentStatus.REVIEWED")
    long countOpenForCandidateUser(@org.springframework.data.repository.query.Param("userId") UUID userId);

    /** Count of REVIEWED assignments for an intern. */
    @Query("SELECT COUNT(wa) FROM WorkAssignment wa " +
            "WHERE wa.intern.user.id = :userId " +
            "AND wa.status = com.anvicorp.api.enums.WorkAssignmentStatus.REVIEWED")
    long countReviewedForCandidateUser(@org.springframework.data.repository.query.Param("userId") UUID userId);

    /**
     * Every assignment for interns whose {@code assignedEvaluator} is the
     * given user, newest first. Optional status filter — pass {@code null}
     * to skip. Fetch-joins intern.user + assignedBy so the list mappers
     * don't lazy-load.
     */
    @Query("SELECT wa FROM WorkAssignment wa " +
            "JOIN FETCH wa.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH wa.assignedBy ab " +
            "WHERE i.assignedEvaluator.id = :evaluatorUserId " +
            "AND (:status IS NULL OR wa.status = :status) " +
            "ORDER BY wa.createdAt DESC")
    List<WorkAssignment> findForEvaluatorAssignedInterns(
            @org.springframework.data.repository.query.Param("evaluatorUserId") UUID evaluatorUserId,
            @org.springframework.data.repository.query.Param("status") com.anvicorp.api.enums.WorkAssignmentStatus status);

    // ── Phase 3 step 8 — engagement-scoped queries (alongside intern_id ones) ──

    /**
     * All assignments for a given engagement, newest first. Same fetch graph
     * as {@link #findForIntern(UUID)} so DTO mappers don't lazy-load. New
     * rows post-step-8 carry an engagement_id; legacy rows (null) stay
     * reachable via the intern-keyed queries above.
     */
    @Query("SELECT wa FROM WorkAssignment wa " +
            "JOIN FETCH wa.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH wa.assignedBy ab " +
            "WHERE wa.engagement.id = :engagementId " +
            "ORDER BY wa.createdAt DESC")
    List<WorkAssignment> findForEngagement(@Param("engagementId") UUID engagementId);
}
