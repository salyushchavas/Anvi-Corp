package com.anvicorp.api.repository;

import com.anvicorp.api.entity.InternEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternEvaluationRepository extends JpaRepository<InternEvaluation, UUID> {

    List<InternEvaluation> findByInternIdOrderByCreatedAtDesc(UUID internId);

    List<InternEvaluation> findByInternIdAndStatusInOrderByCreatedAtDesc(
            UUID internId, Collection<String> statuses);

    List<InternEvaluation> findByEvaluatorIdOrderByCreatedAtDesc(UUID evaluatorId);

    List<InternEvaluation> findByEvaluatorIdAndStatusInOrderByCreatedAtDesc(
            UUID evaluatorId, Collection<String> statuses);

    List<InternEvaluation> findByInternLifecycleIdOrderByCreatedAtDesc(UUID internLifecycleId);

    boolean existsByInternIdAndStatusIn(UUID internId, Collection<String> statuses);

    Optional<InternEvaluation> findByLinkedProjectId(UUID linkedProjectId);

    Optional<InternEvaluation> findFirstByZoomMeetingId(String zoomMeetingId);
}
