package com.anvicorp.api.repository;

import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.enums.I983Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface I983PlanRepository extends JpaRepository<I983Plan, UUID> {

    List<I983Plan> findByCandidateIdOrderByCreatedAtDesc(UUID candidateId);

    /** @deprecated use {@link #findAllWithGraph}. */
    @Deprecated
    Page<I983Plan> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /** @deprecated use {@link #findByStatusWithGraph}. */
    @Deprecated
    Page<I983Plan> findByStatusOrderByUpdatedAtDesc(I983Status status, Pageable pageable);

    /** @deprecated use {@link #findByEntityIdWithGraph}. */
    @Deprecated
    Page<I983Plan> findByEntityIdOrderByUpdatedAtDesc(UUID entityId, Pageable pageable);

    // ── Phase-3 sweep — graph-fetching list queries for the HR/ERM lists. ──

    @Query(value =
            "SELECT p FROM I983Plan p " +
            "JOIN FETCH p.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH p.entity e " +
            "ORDER BY p.updatedAt DESC",
            countQuery = "SELECT COUNT(p) FROM I983Plan p")
    Page<I983Plan> findAllWithGraph(Pageable pageable);

    @Query(value =
            "SELECT p FROM I983Plan p " +
            "JOIN FETCH p.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH p.entity e " +
            "WHERE p.status = :status " +
            "ORDER BY p.updatedAt DESC",
            countQuery = "SELECT COUNT(p) FROM I983Plan p WHERE p.status = :status")
    Page<I983Plan> findByStatusWithGraph(@Param("status") I983Status status, Pageable pageable);

    @Query(value =
            "SELECT p FROM I983Plan p " +
            "JOIN FETCH p.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH p.entity e " +
            "WHERE e.id = :entityId " +
            "ORDER BY p.updatedAt DESC",
            countQuery = "SELECT COUNT(p) FROM I983Plan p WHERE p.entity.id = :entityId")
    Page<I983Plan> findByEntityIdWithGraph(@Param("entityId") UUID entityId, Pageable pageable);

    long countByStatus(I983Status status);

    /** Count of I-983 plans NOT yet DSO_APPROVED. */
    long countByStatusNot(I983Status status);

    /**
     * Single plan with candidate → user and entity eagerly loaded so DTO
     * mappers and controller-side toResponse calls don't trip a
     * LazyInitializationException after the service's transaction closes.
     */
    @Query("SELECT p FROM I983Plan p " +
            "JOIN FETCH p.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH p.entity e " +
            "WHERE p.id = :id")
    Optional<I983Plan> findByIdWithGraph(@Param("id") UUID id);

    /** Same fetch graph for a candidate's own plans, newest first. */
    @Query("SELECT p FROM I983Plan p " +
            "JOIN FETCH p.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH p.entity e " +
            "WHERE c.id = :candidateId " +
            "ORDER BY p.createdAt DESC")
    List<I983Plan> findByCandidateIdWithGraph(@Param("candidateId") UUID candidateId);
}
