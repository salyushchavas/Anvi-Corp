package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Screening;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ScreeningRepository extends JpaRepository<Screening, UUID> {

    /**
     * Fetch by application id (1:1), with the application + posting +
     * candidate graph eagerly joined so the staff results endpoint can format
     * the response without lazy access after the tx closes.
     */
    @Query("""
            select s from Screening s
              left join fetch s.application a
              left join fetch a.jobPosting jp
              left join fetch jp.entity
              left join fetch a.candidate c
              left join fetch c.user
             where a.id = :applicationId
            """)
    Optional<Screening> findByApplicationIdWithGraph(UUID applicationId);

    @Query("""
            select s from Screening s
              left join fetch s.application a
              left join fetch a.jobPosting jp
              left join fetch jp.entity
              left join fetch a.candidate c
              left join fetch c.user
             where s.id = :id
            """)
    Optional<Screening> findByIdWithGraph(UUID id);
}
