package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Project;
import com.anvicorp.api.enums.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    // ── Trainer Phase 2 — lifecycle-tracked slot lookups ─────────────────

    List<Project> findByInternLifecycleIdAndMonthYearOrderByProjectNumberAsc(
            UUID internLifecycleId, String monthYear);

    Optional<Project> findFirstByInternLifecycleIdAndMonthYearAndProjectNumber(
            UUID internLifecycleId, String monthYear, Short projectNumber);

    List<Project> findByInternLifecycleIdOrderByMonthYearDescProjectNumberAsc(
            UUID internLifecycleId);

    /** KT scheduling — used by {@code MeetingHostKeyController}'s
     *  cross-table existence check so {@code /host-start} can return a
     *  fresh start_url for a KT Zoom meeting. Mirrors the same shape on
     *  WeeklyMeeting / Interview / InternEvaluation. */
    Optional<Project> findFirstByKtZoomMeetingId(String ktZoomMeetingId);


    /**
     * Single project with engagement + supervisor + intern's user fetched
     * eagerly so the service / DTO mapper doesn't lazy-load after the
     * transaction closes.
     */
    @Query("SELECT p FROM Project p " +
            "JOIN FETCH p.engagement e " +
            "LEFT JOIN FETCH e.supervisor sv " +
            "JOIN FETCH p.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH p.assignedBy ab " +
            "LEFT JOIN FETCH p.reviewedBy rb " +
            "WHERE p.id = :id")
    Optional<Project> findByIdWithGraph(@Param("id") UUID id);

    /** All projects assigned to a specific intern. Newest first. */
    @Query("SELECT p FROM Project p " +
            "JOIN FETCH p.engagement e " +
            "LEFT JOIN FETCH e.supervisor sv " +
            "JOIN FETCH p.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH p.assignedBy ab " +
            "LEFT JOIN FETCH p.reviewedBy rb " +
            "WHERE i.id = :internId " +
            "ORDER BY p.createdAt DESC")
    List<Project> findByInternIdWithGraph(@Param("internId") UUID internId);

    /**
     * All projects assigned by a given supervisor user. Used for the
     * supervisor's allocation board (and the supervisor-dashboard "projects
     * awaiting review" rollup).
     */
    @Query("SELECT p FROM Project p " +
            "JOIN FETCH p.engagement e " +
            "LEFT JOIN FETCH e.supervisor sv " +
            "JOIN FETCH p.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH p.assignedBy ab " +
            "LEFT JOIN FETCH p.reviewedBy rb " +
            "WHERE ab.id = :supervisorUserId " +
            "ORDER BY p.createdAt DESC")
    List<Project> findByAssignedByIdWithGraph(@Param("supervisorUserId") UUID supervisorUserId);

    /** Count by status — used by the supervisor dashboard action queue. */
    @Query("SELECT COUNT(p) FROM Project p " +
            "WHERE p.assignedBy.id = :supervisorUserId AND p.status = :status")
    long countByAssignedByIdAndStatus(@Param("supervisorUserId") UUID supervisorUserId,
                                      @Param("status") ProjectStatus status);

    /**
     * RM-scoped projects in one of the given statuses, newest first. Joins
     * through engagement.reporting_manager_id — null RM rows are skipped.
     */
    @Query("SELECT p FROM Project p " +
            "JOIN FETCH p.engagement e " +
            "LEFT JOIN FETCH e.reportingManager rm " +
            "LEFT JOIN FETCH e.supervisor sv " +
            "JOIN FETCH p.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH p.assignedBy ab " +
            "WHERE rm.id = :rmUserId AND p.status IN :statuses " +
            "ORDER BY p.updatedAt DESC")
    List<Project> findForReportingManager(@org.springframework.data.repository.query.Param("rmUserId")
                                          UUID rmUserId,
                                          @org.springframework.data.repository.query.Param("statuses")
                                          List<ProjectStatus> statuses);

    /** Count of RM-scoped projects in a single status. */
    @Query("SELECT COUNT(p) FROM Project p " +
            "WHERE p.engagement.reportingManager.id = :rmUserId AND p.status = :status")
    long countByReportingManagerAndStatus(
            @Param("rmUserId") UUID rmUserId,
            @Param("status") ProjectStatus status);

    /** Count of RM-scoped projects completed at-or-after the given instant. */
    @Query("SELECT COUNT(p) FROM Project p " +
            "WHERE p.engagement.reportingManager.id = :rmUserId " +
            "  AND p.status = com.anvicorp.api.enums.ProjectStatus.COMPLETED " +
            "  AND p.completedAt >= :since")
    long countCompletedSinceForReportingManager(
            @Param("rmUserId") UUID rmUserId,
            @Param("since") java.time.Instant since);

    // ── Catalog queries (new Project Catalog + Assignment module) ──────────
    //
    // Catalog projects are the rows with created_by_id set; legacy
    // single-allocation rows leave it null. We don't filter the legacy
    // queries on created_by_id IS NULL — they still show every project so
    // existing views are unchanged.

    List<Project> findByCreatedByIdOrderByCreatedAtDesc(UUID createdById);

    @org.springframework.data.jpa.repository.Query(
            "SELECT p FROM Project p WHERE p.createdById IS NOT NULL "
                    + "ORDER BY p.createdAt DESC")
    List<Project> findByCreatedByIdNotNullOrderByCreatedAtDesc();

    // ── Role-based queries (no per-engagement RM/supervisor FK filter) ──────
    //
    // The post-refactor supervision model treats any TECHNICAL_EVALUATOR /
    // REPORTING_MANAGER as authoritative for every relevant row. The
    // findFor*-style queries above are retained for special-case scopes but
    // are no longer the default the dashboards consult.

    @Query("SELECT p FROM Project p " +
            "JOIN FETCH p.engagement e " +
            "LEFT JOIN FETCH e.reportingManager rm " +
            "LEFT JOIN FETCH e.supervisor sv " +
            "JOIN FETCH p.intern i " +
            "JOIN FETCH i.user iu " +
            "JOIN FETCH p.assignedBy ab " +
            "WHERE p.status IN :statuses " +
            "ORDER BY p.updatedAt DESC")
    List<Project> findAllByStatusInWithGraph(@Param("statuses") List<ProjectStatus> statuses);

    long countByStatus(ProjectStatus status);

    @Query("SELECT COUNT(p) FROM Project p " +
            "WHERE p.status = com.anvicorp.api.enums.ProjectStatus.COMPLETED " +
            "  AND p.completedAt >= :since")
    long countCompletedSince(@Param("since") java.time.Instant since);
}
