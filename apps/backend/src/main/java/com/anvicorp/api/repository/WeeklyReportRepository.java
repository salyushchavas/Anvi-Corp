package com.anvicorp.api.repository;

import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.WeeklyReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, UUID> {

    /**
     * One report per (intern, week_start) by unique constraint. Returns the
     * row or empty — callers decide whether to create a new DRAFT.
     */
    Optional<WeeklyReport> findByInternIdAndWeekStart(UUID internId, LocalDate weekStart);

    /**
     * Newest first list of all reports for an intern. Used by the
     * {@code /me} (intern self) and {@code /intern/{candidateId}}
     * (reviewer) read endpoints; fetch-joins the reviewer + intern user
     * so the response mapper doesn't lazy-load after the tx closes.
     */
    @Query("SELECT r FROM WeeklyReport r " +
            "JOIN FETCH r.intern i " +
            "JOIN FETCH i.user u " +
            "LEFT JOIN FETCH r.reviewedBy rv " +
            "LEFT JOIN FETCH r.verifiedBy vb " +
            "WHERE i.id = :internId " +
            "ORDER BY r.weekStart DESC, r.createdAt DESC")
    List<WeeklyReport> findByInternIdWithGraph(@Param("internId") UUID internId);

    /**
     * Single report with the intern + user + reviewer graph eagerly joined.
     * Used by the edit / submit / return / approve / verify paths so the
     * response never triggers a LazyInitializationException under
     * {@code spring.jpa.open-in-view=false}.
     */
    @Query("SELECT r FROM WeeklyReport r " +
            "JOIN FETCH r.intern i " +
            "JOIN FETCH i.user u " +
            "LEFT JOIN FETCH r.reviewedBy rv " +
            "LEFT JOIN FETCH r.verifiedBy vb " +
            "WHERE r.id = :id")
    Optional<WeeklyReport> findByIdWithGraph(@Param("id") UUID id);

    /**
     * Queue view. Powers the ERM verify queue (status = SUBMITTED) and the
     * Evaluator approve queue (status = VERIFIED). Sorted by submittedAt asc
     * so the oldest waiting row surfaces first (matches the timesheet
     * reviewer expectation — FIFO within a status).
     */
    @Query("SELECT r FROM WeeklyReport r " +
            "JOIN FETCH r.intern i " +
            "JOIN FETCH i.user u " +
            "LEFT JOIN FETCH r.reviewedBy rv " +
            "LEFT JOIN FETCH r.verifiedBy vb " +
            "WHERE r.status = :status " +
            "ORDER BY r.submittedAt ASC NULLS LAST, r.weekStart ASC")
    List<WeeklyReport> findAllByStatusWithGraph(@Param("status") WeeklyReportStatus status);
}
