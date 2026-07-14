package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ProjectAssignmentEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectAssignmentEventLogRepository
        extends JpaRepository<ProjectAssignmentEventLog, UUID> {

    List<ProjectAssignmentEventLog> findByProjectIdOrderByCreatedAtAsc(UUID projectId);
}
