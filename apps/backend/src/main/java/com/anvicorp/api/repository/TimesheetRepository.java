package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Timesheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TimesheetRepository extends JpaRepository<Timesheet, UUID> {

    /**
     * Staff list for an intern, newest first. Fetch-joins intern.user (for any
     * downstream name lookups) and approvedBy so the DTO mapper can render
     * approvedByName without re-opening a session.
     */
    @Query("SELECT t FROM Timesheet t " +
            "JOIN FETCH t.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH t.approvedBy ab " +
            "WHERE i.id = :candidateId " +
            "ORDER BY t.weekStart DESC, t.createdAt DESC")
    List<Timesheet> findForIntern(@Param("candidateId") UUID candidateId);

    /** Candidate's own timesheets, newest first. */
    @Query("SELECT t FROM Timesheet t " +
            "JOIN FETCH t.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH t.approvedBy ab " +
            "WHERE iu.id = :userId " +
            "ORDER BY t.weekStart DESC, t.createdAt DESC")
    List<Timesheet> findForCandidateUser(@Param("userId") UUID userId);

    /**
     * Full fetch graph for a single timesheet so the candidate-side ownership
     * check ({@code t.intern.user.id == caller.id}) and DTO mapping never touch
     * a detached lazy proxy.
     */
    @Query("SELECT t FROM Timesheet t " +
            "JOIN FETCH t.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH t.approvedBy ab " +
            "WHERE t.id = :id")
    Optional<Timesheet> findByIdWithGraph(@Param("id") UUID id);

    /** Sum of APPROVED hours for an intern. NULL when there are none. */
    @Query("SELECT COALESCE(SUM(t.hours), 0) FROM Timesheet t " +
            "WHERE t.intern.id = :candidateId " +
            "AND t.status = com.anvicorp.api.enums.TimesheetStatus.APPROVED")
    BigDecimal sumApprovedHoursForIntern(@Param("candidateId") UUID candidateId);

    /** Sum of APPROVED hours for an intern resolved by their User id. */
    @Query("SELECT COALESCE(SUM(t.hours), 0) FROM Timesheet t " +
            "WHERE t.intern.user.id = :userId " +
            "AND t.status = com.anvicorp.api.enums.TimesheetStatus.APPROVED")
    BigDecimal sumApprovedHoursForCandidateUser(@Param("userId") UUID userId);

    // ── Phase 3 step 8 — engagement-scoped queries (alongside intern_id ones) ──

    /**
     * All timesheets for a given engagement, newest week first. Same fetch
     * graph as the intern-keyed reads. Legacy rows with null engagement_id
     * stay accessible via {@link #findForIntern(UUID)} until step-11 backfill.
     */
    @Query("SELECT t FROM Timesheet t " +
            "JOIN FETCH t.intern i " +
            "JOIN FETCH i.user iu " +
            "LEFT JOIN FETCH t.approvedBy ab " +
            "WHERE t.engagement.id = :engagementId " +
            "ORDER BY t.weekStart DESC, t.createdAt DESC")
    List<Timesheet> findForEngagement(@Param("engagementId") UUID engagementId);

    /** Sum of APPROVED hours scoped to one engagement (post-step-8 rows). */
    @Query("SELECT COALESCE(SUM(t.hours), 0) FROM Timesheet t " +
            "WHERE t.engagement.id = :engagementId " +
            "AND t.status = com.anvicorp.api.enums.TimesheetStatus.APPROVED")
    BigDecimal sumApprovedHoursForEngagement(@Param("engagementId") UUID engagementId);

    /** Exact-week lookup used by the daily timesheet-due scheduler. */
    Optional<Timesheet> findByInternIdAndWeekStart(UUID internId, LocalDate weekStart);

    /** Exact-week lookup keyed by the candidate's User id (intern face). */
    @Query("SELECT t FROM Timesheet t "
            + "JOIN FETCH t.intern i "
            + "JOIN FETCH i.user iu "
            + "LEFT JOIN FETCH t.approvedBy ab "
            + "WHERE iu.id = :userId AND t.weekStart = :weekStart")
    Optional<Timesheet> findByCandidateUserAndWeek(@Param("userId") UUID userId,
                                                   @Param("weekStart") LocalDate weekStart);

    /**
     * SUBMITTED timesheets scoped to a Reporting Manager — joins through the
     * intern's active engagement. Newest submission first.
     */
    @Query("SELECT t FROM Timesheet t "
            + "JOIN FETCH t.intern i "
            + "JOIN FETCH i.user iu "
            + "LEFT JOIN FETCH t.engagement e "
            + "WHERE t.status = com.anvicorp.api.enums.TimesheetStatus.SUBMITTED "
            + "  AND e.reportingManager.id = :rmUserId "
            + "ORDER BY t.weekStart DESC, t.createdAt DESC")
    List<Timesheet> findSubmittedForReportingManager(@Param("rmUserId") UUID rmUserId);

    /** All SUBMITTED timesheets (admin override). Newest first. */
    @Query("SELECT t FROM Timesheet t "
            + "JOIN FETCH t.intern i "
            + "JOIN FETCH i.user iu "
            + "LEFT JOIN FETCH t.engagement e "
            + "WHERE t.status = com.anvicorp.api.enums.TimesheetStatus.SUBMITTED "
            + "ORDER BY t.weekStart DESC, t.createdAt DESC")
    List<Timesheet> findAllSubmitted();
}
