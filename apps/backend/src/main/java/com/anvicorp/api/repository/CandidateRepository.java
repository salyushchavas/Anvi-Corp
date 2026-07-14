package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CandidateRepository
        extends JpaRepository<Candidate, UUID>,
        JpaSpecificationExecutor<Candidate> {
    Optional<Candidate> findByUserId(UUID userId);

    /**
     * Candidate with {@code assignedEvaluator} eagerly loaded. Used by the
     * Supervised Work overview so the DTO mapper can read the evaluator's
     * fullName without tripping a LazyInitializationException once the
     * read-only transaction closes.
     */
    @Query("SELECT c FROM Candidate c " +
            "LEFT JOIN FETCH c.assignedEvaluator " +
            "WHERE c.user.id = :userId")
    Optional<Candidate> findByUserIdWithEvaluator(@Param("userId") UUID userId);

    // The previous JPQL searchWithUser query was replaced by
    // {@link CandidateSpecifications#nameOrEmailMatches(String)} — it
    // composes name/email matching with the optional-filter pattern and
    // applies JOIN FETCH only on the data query, avoiding the count-query
    // lazy-load + ":search IS NULL" predicate pitfalls.

    /** Single candidate with the user graph eagerly loaded. */
    @Query("SELECT c FROM Candidate c " +
            "JOIN FETCH c.user u " +
            "WHERE c.id = :id")
    Optional<Candidate> findByIdWithUser(@Param("id") UUID id);
}
