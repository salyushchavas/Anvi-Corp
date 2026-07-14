package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ProjectAssignment;
import com.anvicorp.api.enums.ProjectAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectAssignmentRepository
        extends JpaRepository<ProjectAssignment, UUID> {

    /** Intern view — every assignment to this user, newest first. */
    List<ProjectAssignment> findByInternIdOrderByAssignmentDateDescCreatedAtDesc(UUID internId);

    /** Project detail page — every assignment under a given catalog project. */
    List<ProjectAssignment> findByProjectIdOrderByAssignmentDateDescCreatedAtDesc(UUID projectId);

    /** TE view — every assignment this user created. */
    List<ProjectAssignment> findByAssignedByIdOrderByAssignmentDateDescCreatedAtDesc(UUID assignedById);

    /**
     * Status-filtered roster for reviewer queues (TE Submitted, RM viva).
     * Ordered by updatedAt so freshly-transitioned rows surface first.
     */
    List<ProjectAssignment> findByStatusInOrderByUpdatedAtDesc(
            Collection<ProjectAssignmentStatus> statuses);

    long countByProjectId(UUID projectId);
}
