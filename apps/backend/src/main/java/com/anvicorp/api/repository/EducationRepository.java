package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Education;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EducationRepository extends JpaRepository<Education, UUID> {

    /** Every education row a candidate has. Ordered newest first for stable rendering. */
    List<Education> findByCandidateIdOrderByGraduationDateDescCreatedAtDesc(UUID candidateId);

    /** The single primary row for a candidate (invariant enforced in the service layer). */
    Optional<Education> findFirstByCandidateIdAndIsPrimary(UUID candidateId, Boolean isPrimary);

    long countByCandidateId(UUID candidateId);
}
