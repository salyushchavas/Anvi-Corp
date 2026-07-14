package com.anvicorp.api.repository;

import com.anvicorp.api.entity.I983Evaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Evaluator Phase 0 — repository scaffold for the dedicated I-983 entity.
 * Phase 3 will add query methods (find-by-evaluator, find-overdue, etc.).
 */
@Repository
public interface I983EvaluationRepository
        extends JpaRepository<I983Evaluation, UUID> {

    List<I983Evaluation> findByInternLifecycleIdOrderByCreatedAtDesc(
            UUID internLifecycleId);

    List<I983Evaluation> findByEvaluatorIdOrderByCreatedAtDesc(UUID evaluatorId);
}
