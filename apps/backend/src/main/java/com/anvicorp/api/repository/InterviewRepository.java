package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.enums.InterviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewRepository extends JpaRepository<Interview, UUID>,
        JpaSpecificationExecutor<Interview> {

    List<Interview> findByApplicationIdOrderByScheduledAtDesc(UUID applicationId);

    Page<Interview> findByInterviewerIdAndStatusOrderByScheduledAtAsc(
            UUID interviewerId, InterviewStatus status, Pageable pageable);

    Page<Interview> findByScheduledAtAfterOrderByScheduledAtAsc(Instant cutoff, Pageable pageable);

    Page<Interview> findByScheduledAtBeforeOrderByScheduledAtDesc(Instant cutoff, Pageable pageable);

    long countByApplicationIdAndStatus(UUID applicationId, InterviewStatus status);

    boolean existsByApplicationIdAndStatus(UUID applicationId, InterviewStatus status);

    Optional<Interview> findFirstByZoomMeetingId(String zoomMeetingId);

    @Query("SELECT i FROM Interview i " +
            "JOIN FETCH i.application a " +
            "JOIN FETCH a.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH i.interviewer ir " +
            "WHERE u.id = :userId " +
            "ORDER BY i.scheduledAt DESC")
    List<Interview> findAllForCandidateUser(@Param("userId") UUID userId);

    /**
     * Single interview with application → candidate → user, application →
     * jobPosting, and interviewer eagerly loaded — used by the getDetail path
     * so the DTO mapper doesn't lazy-load after the transaction closes.
     */
    @Query("SELECT i FROM Interview i " +
            "JOIN FETCH i.application a " +
            "JOIN FETCH a.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH i.interviewer ir " +
            "WHERE i.id = :id")
    Optional<Interview> findByIdWithGraph(@Param("id") UUID id);

    /**
     * Interview reminder cron — SCHEDULED interviews whose start sits inside
     * the supplied window. The scheduler runs hourly and passes (now+23h,
     * now+25h] so each interview falls into the window roughly once; the
     * notification idempotency table makes duplicate hits a no-op.
     */
    @Query("SELECT i FROM Interview i " +
            "JOIN FETCH i.application a " +
            "JOIN FETCH a.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH jp.entity je " +
            "LEFT JOIN FETCH i.interviewer ir " +
            "WHERE i.status = com.anvicorp.api.enums.InterviewStatus.SCHEDULED " +
            "  AND i.scheduledAt BETWEEN :windowStart AND :windowEnd")
    List<Interview> findScheduledBetweenWithGraph(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    // The staff list query was rewritten as a JPA Specification — see
    // {@link InterviewSpecifications#withFilters}. Reason: the previous
    // {@code @Query} used the {@code :param IS NULL OR col = :param} pattern
    // with a nullable {@code Instant}, which surfaced Postgres
    // SQLSTATE 42P18 ("could not determine data type of parameter $N") when
    // the cutoff was null. Specifications add predicates only when the value
    // is present, so null filters never bind. Callers use
    // {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor#findAll(Specification, Pageable)}.
}
