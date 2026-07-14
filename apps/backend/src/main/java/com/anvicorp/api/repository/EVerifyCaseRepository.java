package com.anvicorp.api.repository;

import com.anvicorp.api.entity.EVerifyCase;
import com.anvicorp.api.enums.EVerifyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EVerifyCaseRepository extends JpaRepository<EVerifyCase, UUID> {

    Optional<EVerifyCase> findByI9FormId(UUID i9FormId);

    /** @deprecated use {@link #findAllWithGraph} for HR list reads. */
    @Deprecated
    Page<EVerifyCase> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** @deprecated use {@link #findByStatusWithGraph} for HR list reads. */
    @Deprecated
    Page<EVerifyCase> findByStatusOrderByCreatedAtDesc(EVerifyStatus status, Pageable pageable);

    boolean existsByI9FormId(UUID i9FormId);

    /** Count of E-Verify cases in any of the supplied statuses. */
    long countByStatusIn(java.util.Collection<EVerifyStatus> statuses);

    // ── Phase-3 sweep — fetch the toSummary/toResponse graph eagerly so
    // candidate name/email reads don't 500 under open-in-view=false. ──

    /**
     * HR list query with the toSummary fetch graph (I-9 → candidate → user)
     * eagerly loaded. Single-valued joins are safe under Pageable.
     */
    @Query(value =
            "SELECT c FROM EVerifyCase c " +
            "JOIN FETCH c.i9Form i9 " +
            "JOIN FETCH i9.candidate cand " +
            "JOIN FETCH cand.user u " +
            "ORDER BY c.createdAt DESC",
            countQuery = "SELECT COUNT(c) FROM EVerifyCase c")
    Page<EVerifyCase> findAllWithGraph(Pageable pageable);

    @Query(value =
            "SELECT c FROM EVerifyCase c " +
            "JOIN FETCH c.i9Form i9 " +
            "JOIN FETCH i9.candidate cand " +
            "JOIN FETCH cand.user u " +
            "WHERE c.status = :status " +
            "ORDER BY c.createdAt DESC",
            countQuery = "SELECT COUNT(c) FROM EVerifyCase c WHERE c.status = :status")
    Page<EVerifyCase> findByStatusWithGraph(@Param("status") EVerifyStatus status, Pageable pageable);

    /**
     * Detail-by-id with the full graph for {@code toResponse}. The detail
     * page reads candidate name + email which sit two levels deep through I-9.
     */
    @Query("SELECT c FROM EVerifyCase c " +
            "JOIN FETCH c.i9Form i9 " +
            "JOIN FETCH i9.candidate cand " +
            "JOIN FETCH cand.user u " +
            "WHERE c.id = :id")
    Optional<EVerifyCase> findByIdWithGraph(@Param("id") UUID id);

    /** I-9-keyed lookup with the same fetch graph; used by {@code getByI9FormId}. */
    @Query("SELECT c FROM EVerifyCase c " +
            "JOIN FETCH c.i9Form i9 " +
            "JOIN FETCH i9.candidate cand " +
            "JOIN FETCH cand.user u " +
            "WHERE i9.id = :i9FormId")
    Optional<EVerifyCase> findByI9FormIdWithGraph(@Param("i9FormId") UUID i9FormId);
}
