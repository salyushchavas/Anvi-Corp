package com.anvicorp.api.repository;

import com.anvicorp.api.entity.EvaluationAmendment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvaluationAmendmentRepository extends JpaRepository<EvaluationAmendment, UUID> {

    List<EvaluationAmendment> findByEvaluationIdOrderByAmendedAtAsc(UUID evaluationId);
}
