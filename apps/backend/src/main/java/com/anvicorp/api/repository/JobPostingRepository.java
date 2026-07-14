package com.anvicorp.api.repository;

import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.enums.JobPostingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobPostingRepository
        extends JpaRepository<JobPosting, UUID>,
        JpaSpecificationExecutor<JobPosting> {
    List<JobPosting> findByStatus(JobPostingStatus status);
    List<JobPosting> findByEntityId(UUID entityId);

    Page<JobPosting> findByStatus(JobPostingStatus status, Pageable pageable);

    Optional<JobPosting> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
