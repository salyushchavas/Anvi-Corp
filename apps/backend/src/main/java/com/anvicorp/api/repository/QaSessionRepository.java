package com.anvicorp.api.repository;

import com.anvicorp.api.entity.QaSession;
import com.anvicorp.api.enums.QaSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QaSessionRepository extends JpaRepository<QaSession, UUID> {

    /**
     * Authorization lookup for {@code /api/v1/meetings/{id}/host-start} —
     * the controller refuses to proxy a fresh Zoom start_url unless the
     * caller's meeting id maps to one of our own meeting rows. Mirrors
     * {@code DoubtRequestRepository.findFirstByZoomMeetingId}.
     */
    Optional<QaSession> findFirstByZoomMeetingId(String zoomMeetingId);

    /**
     * Full fetch graph for a single session — project + engagement + RM +
     * intern user — so the service / DTO mapper never lazy-loads after the
     * transaction closes.
     */
    @Query("SELECT s FROM QaSession s "
            + "JOIN FETCH s.project p "
            + "JOIN FETCH p.engagement e "
            + "LEFT JOIN FETCH e.reportingManager rm "
            + "JOIN FETCH p.intern i "
            + "JOIN FETCH i.user iu "
            + "LEFT JOIN FETCH s.scheduledBy sb "
            + "LEFT JOIN FETCH s.conductedBy cb "
            + "WHERE s.id = :id")
    Optional<QaSession> findByIdWithGraph(@Param("id") UUID id);

    /** All sessions on a project, newest first. */
    @Query("SELECT s FROM QaSession s "
            + "JOIN FETCH s.project p "
            + "JOIN FETCH p.engagement e "
            + "LEFT JOIN FETCH e.reportingManager rm "
            + "JOIN FETCH p.intern i "
            + "JOIN FETCH i.user iu "
            + "WHERE p.id = :projectId "
            + "ORDER BY s.scheduledAt DESC, s.createdAt DESC")
    List<QaSession> findByProjectIdWithGraph(@Param("projectId") UUID projectId);

    /** Active sessions on RM's projects (SCHEDULED or CONDUCTED). */
    @Query("SELECT s FROM QaSession s "
            + "JOIN FETCH s.project p "
            + "JOIN FETCH p.engagement e "
            + "JOIN FETCH p.intern i "
            + "JOIN FETCH i.user iu "
            + "WHERE e.reportingManager.id = :rmUserId "
            + "  AND s.status IN :statuses "
            + "ORDER BY s.scheduledAt ASC")
    List<QaSession> findActiveForRm(@Param("rmUserId") UUID rmUserId,
                                    @Param("statuses") List<QaSessionStatus> statuses);

    /**
     * Role-based variant — every active session across all engagements.
     * The post-refactor RM dashboard uses this so any REPORTING_MANAGER
     * sees the full queue, not just sessions for engagements where they
     * are the assigned RM.
     */
    @Query("SELECT s FROM QaSession s "
            + "JOIN FETCH s.project p "
            + "JOIN FETCH p.engagement e "
            + "JOIN FETCH p.intern i "
            + "JOIN FETCH i.user iu "
            + "WHERE s.status IN :statuses "
            + "ORDER BY s.scheduledAt ASC")
    List<QaSession> findAllByStatusInWithGraph(@Param("statuses") List<QaSessionStatus> statuses);

    /**
     * Intern's pending-Q&A surface — sessions whose project is in the
     * given list and whose status is in the given set, newest scheduled
     * first. Used by {@code ProjectAssignmentService.mapWithGraph} to
     * surface the join link + scheduled time on each intern's project
     * row in one round-trip rather than N+1.
     */
    @Query("SELECT s FROM QaSession s "
            + "LEFT JOIN FETCH s.scheduledBy sb "
            + "WHERE s.project.id IN :projectIds "
            + "  AND s.status IN :statuses "
            + "ORDER BY s.scheduledAt DESC")
    List<QaSession> findByProjectIdsAndStatusIn(
            @Param("projectIds") java.util.Collection<UUID> projectIds,
            @Param("statuses") List<QaSessionStatus> statuses);
}
