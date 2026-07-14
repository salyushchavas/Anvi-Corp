package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ProjectTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectTaskRepository extends JpaRepository<ProjectTask, UUID> {

    List<ProjectTask> findByProjectIdOrderBySortOrderAsc(UUID projectId);

    long countByProjectIdAndDoneTrue(UUID projectId);

    long countByProjectId(UUID projectId);

    void deleteByProjectId(UUID projectId);
}
