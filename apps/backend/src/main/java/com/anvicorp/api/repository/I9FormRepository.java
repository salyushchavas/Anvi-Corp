package com.anvicorp.api.repository;

import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.enums.I9Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface I9FormRepository extends JpaRepository<I9Form, UUID> {

    Optional<I9Form> findByCandidateId(UUID candidateId);

    /**
     * @deprecated The HR list path needs candidate + user fetch-joined so the
     * summary mapper can read fullName/email without a LazyInit 500 under
     * {@code spring.jpa.open-in-view=false}. Use {@link #findAllWithGraph} or
     * {@link #findByStatusWithGraph} instead. Kept for any one-off internal call.
     */
    @Deprecated
    Page<I9Form> findByStatusOrderByUpdatedAtDesc(I9Status status, Pageable pageable);

    /** @deprecated use {@link #findAllWithGraph} for HR list reads. */
    @Deprecated
    Page<I9Form> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /**
     * Phase-3 sweep — HR list query with the toSummary fetch graph
     * (candidate + user) eagerly loaded. Single-valued joins are safe under
     * Pageable. Explicit {@code countQuery} keeps pagination O(1).
     */
    @Query(value =
            "SELECT f FROM I9Form f " +
            "JOIN FETCH f.candidate c " +
            "JOIN FETCH c.user u " +
            "ORDER BY f.updatedAt DESC",
            countQuery = "SELECT COUNT(f) FROM I9Form f")
    Page<I9Form> findAllWithGraph(Pageable pageable);

    @Query(value =
            "SELECT f FROM I9Form f " +
            "JOIN FETCH f.candidate c " +
            "JOIN FETCH c.user u " +
            "WHERE f.status = :status " +
            "ORDER BY f.updatedAt DESC",
            countQuery = "SELECT COUNT(f) FROM I9Form f WHERE f.status = :status")
    Page<I9Form> findByStatusWithGraph(@Param("status") I9Status status, Pageable pageable);

    List<I9Form> findByStatusAndFirstDayOfEmploymentBefore(I9Status status, LocalDate cutoff);

    /** Count of I-9 forms NOT yet in {@link I9Status#COMPLETED}. */
    long countByStatusNot(I9Status status);

    /**
     * Single form with candidate → user eagerly loaded so DTO mappers and
     * controller-side toResponse calls don't trip a LazyInitializationException
     * after the service's transaction closes.
     */
    @Query("SELECT f FROM I9Form f " +
            "JOIN FETCH f.candidate c " +
            "JOIN FETCH c.user u " +
            "WHERE f.id = :id")
    Optional<I9Form> findByIdWithGraph(@Param("id") UUID id);

    /** Same fetch graph keyed by candidate id, used by getMyForm/getOrCreateForCandidate. */
    @Query("SELECT f FROM I9Form f " +
            "JOIN FETCH f.candidate c " +
            "JOIN FETCH c.user u " +
            "WHERE c.id = :candidateId")
    Optional<I9Form> findByCandidateIdWithGraph(@Param("candidateId") UUID candidateId);
}
