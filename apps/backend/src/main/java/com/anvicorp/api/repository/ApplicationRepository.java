package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.enums.ApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApplicationRepository
        extends JpaRepository<Application, UUID>,
        JpaSpecificationExecutor<Application> {
    List<Application> findByCandidateId(UUID candidateId);
    List<Application> findByJobPostingId(UUID jobPostingId);

    /**
     * Direct lookup of every Application owned by a given User (joined
     * through Candidate). Used by the intern dashboard's
     * selection-acknowledgment context so the SelectionAckCard's
     * visibility doesn't silently break when the standalone
     * {@code CandidateRepository.findByUserId} lookup returns empty
     * — pulls the same row set the ERM-side offer flow operates on,
     * without depending on the cached Candidate→User link.
     */
    @Query("SELECT a FROM Application a " +
            "WHERE a.candidate.user.id = :userId " +
            "ORDER BY a.appliedAt DESC")
    List<Application> findByCandidateUserIdOrderByAppliedAtDesc(@Param("userId") UUID userId);

    /**
     * Email-keyed fallback for the intern dashboard's selection-ack
     * picker. Used when the user-id lookup above returns empty — which
     * happens when a candidate's {@code Candidate.user_id} points at a
     * stale duplicate User row even though the logged-in caller has the
     * same email. Without this fallback the dashboard would silently
     * say "Awaiting interview feedback" while the ERM Send-Offer gate
     * (which loads the application by id, no user join) correctly
     * 409's on the same application.
     */
    @Query("SELECT a FROM Application a " +
            "WHERE LOWER(a.candidate.user.email) = LOWER(:email) " +
            "ORDER BY a.appliedAt DESC")
    List<Application> findByCandidateUserEmailOrderByAppliedAtDesc(@Param("email") String email);

    /** Phase 3 step 11 — bounded lookup for the engagement backfill runner. */
    List<Application> findByStatusIn(java.util.Collection<ApplicationStatus> statuses);

    /**
     * Candidate's applications with the JobPosting + StaffingEntity chain
     * eagerly loaded — used by the staff-side candidate detail page so the
     * mapper can read posting title + entity name without lazy-loading.
     */
    @Query("SELECT a FROM Application a " +
            "JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH jp.entity e " +
            "WHERE a.candidate.id = :candidateId " +
            "ORDER BY a.appliedAt DESC")
    List<Application> findByCandidateIdWithPosting(@Param("candidateId") UUID candidateId);

    boolean existsByCandidateIdAndJobPostingId(UUID candidateId, UUID jobPostingId);

    /**
     * "Already applied" guard that ignores WITHDRAWN rows so an intern who
     * withdrew a prior application can submit a fresh one for the same
     * posting. Paired with the partial UNIQUE
     * {@code uk_application_candidate_job_posting_active} maintained by
     * {@code SchemaFixupRunner.ensureApplicationsReapplyAfterWithdrawSchema}.
     */
    boolean existsByCandidateIdAndJobPostingIdAndStatusNot(
            UUID candidateId, UUID jobPostingId, ApplicationStatus status);

    boolean existsByResumeId(UUID resumeId);
    boolean existsByStatus(ApplicationStatus status);

    long countByStatus(ApplicationStatus status);

    /** Distinct candidates with at least one application in the given status. */
    @Query("SELECT COUNT(DISTINCT a.candidate.id) FROM Application a WHERE a.status = :status")
    long countDistinctCandidatesByStatus(@Param("status") ApplicationStatus status);

    /**
     * Batch applicant-count lookup for the admin postings page. Returns one
     * row per posting that has at least one application: {@code [postingId, count]}.
     * Postings with zero applications are absent from the result — callers
     * fill those in as 0. One query, O(1) for any page size.
     */
    @Query("SELECT a.jobPosting.id, COUNT(a) FROM Application a " +
            "WHERE a.jobPosting.id IN :postingIds " +
            "GROUP BY a.jobPosting.id")
    List<Object[]> countByJobPostingIdIn(@Param("postingIds") java.util.Collection<UUID> postingIds);

    @Query("SELECT a FROM Application a " +
            "WHERE (:status IS NULL OR a.status = :status) " +
            "AND (:jobPostingId IS NULL OR a.jobPosting.id = :jobPostingId)")
    Page<Application> search(@Param("status") ApplicationStatus status,
                             @Param("jobPostingId") UUID jobPostingId,
                             Pageable pageable);

    /**
     * Hired applications with their full candidate + posting + entity graph
     * eagerly loaded, for the Supervised Interns roster (Group C). A candidate
     * may have multiple HIRED rows (rare, but allowed); the service dedupes by
     * candidate id, keeping the most recent by statusUpdatedAt thanks to the
     * ORDER BY clause.
     */
    /**
     * Postgres 18 fix — same as {@code EngagementRepository.findRosterByStatusIn}:
     * the previous LOWER(CONCAT('%', :search, '%')) tripped type-inference
     * (SQLSTATE 42883 "function lower(bytea) does not exist"). Service layer
     * now precomputes the lowercase wildcard pattern and binds it directly.
     * Null skips the filter.
     */
    @Query("SELECT a FROM Application a " +
            "JOIN FETCH a.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH c.assignedEvaluator ae " +
            "JOIN FETCH a.jobPosting jp " +
            "JOIN FETCH jp.entity e " +
            "WHERE a.status = com.anvicorp.api.enums.ApplicationStatus.HIRED " +
            "AND (:entityId IS NULL OR e.id = :entityId) " +
            "AND (:searchPattern IS NULL " +
            "     OR LOWER(u.fullName) LIKE :searchPattern " +
            "     OR LOWER(u.email)    LIKE :searchPattern) " +
            "ORDER BY a.statusUpdatedAt DESC")
    List<Application> findHiredInterns(@Param("entityId") UUID entityId,
                                       @Param("searchPattern") String searchPattern);

    /**
     * Hired applications for interns whose {@code assignedEvaluator} is the
     * given user. Used by the Evaluator area to scope lists/dashboard to the
     * caller's own roster. Same fetch graph as {@link #findHiredInterns} so
     * mappers can read position + entityName without lazy-loading.
     */
    @Query("SELECT a FROM Application a " +
            "JOIN FETCH a.candidate c " +
            "JOIN FETCH c.user u " +
            "JOIN FETCH a.jobPosting jp " +
            "JOIN FETCH jp.entity e " +
            "WHERE a.status = com.anvicorp.api.enums.ApplicationStatus.HIRED " +
            "AND c.assignedEvaluator.id = :evaluatorUserId " +
            "ORDER BY a.statusUpdatedAt DESC")
    List<Application> findHiredInternsForEvaluator(@Param("evaluatorUserId") UUID evaluatorUserId);
}
