package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.enums.OfferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OfferRepository extends JpaRepository<Offer, UUID> {

    List<Offer> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId);

    Page<Offer> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Offer> findByStatusOrderByCreatedAtDesc(OfferStatus status, Pageable pageable);

    List<Offer> findByApplication_Candidate_IdOrderByCreatedAtDesc(UUID candidateId);

    List<Offer> findByApplication_Candidate_User_IdOrderByCreatedAtDesc(UUID userId);

    List<Offer> findByStatusAndExpiresAtBefore(OfferStatus status, Instant cutoff);

    /**
     * Single offer with the full application → candidate → user and
     * application → jobPosting → entity chain eagerly loaded so DTO mappers
     * and controller-side toCandidateResponse calls don't trip a
     * LazyInitializationException after the service's transaction closes.
     */
    @Query("SELECT o FROM Offer o " +
            "JOIN FETCH o.application a " +
            "JOIN FETCH a.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH jp.entity e " +
            "WHERE o.id = :id")
    Optional<Offer> findByIdWithGraph(@Param("id") UUID id);

    /** Same fetch graph, but for a candidate's own offers list. Newest first. */
    @Query("SELECT o FROM Offer o " +
            "JOIN FETCH o.application a " +
            "JOIN FETCH a.candidate c " +
            "JOIN FETCH c.user u " +
            "LEFT JOIN FETCH a.jobPosting jp " +
            "LEFT JOIN FETCH jp.entity e " +
            "WHERE u.id = :userId " +
            "ORDER BY o.createdAt DESC")
    List<Offer> findByCandidateUserIdWithGraph(@Param("userId") UUID userId);

    /** Phase 3 — one offer per application guard. */
    boolean existsByApplicationId(UUID applicationId);
}
