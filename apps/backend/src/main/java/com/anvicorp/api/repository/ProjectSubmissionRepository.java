package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ProjectSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectSubmissionRepository extends JpaRepository<ProjectSubmission, UUID> {

    List<ProjectSubmission> findByProjectIdOrderBySubmittedAtDesc(UUID projectId);

    /** Trainer Phase 3 — Pending Reviews queue: any row where the trainer
     *  hasn't recorded a terminal decision yet (NULL or NO_ACTION_YET). */
    long countByProjectIdAndTrainerDecisionIsNull(UUID projectId);

    int countByProjectId(UUID projectId);
}
