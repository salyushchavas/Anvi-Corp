package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ProjectTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectTemplateRepository
        extends JpaRepository<ProjectTemplate, UUID> {

    List<ProjectTemplate> findByPublishedTrueAndArchivedAtIsNullOrderByTitleAsc();

    List<ProjectTemplate> findByTechnologyAreaAndPublishedTrueAndArchivedAtIsNullOrderByTitleAsc(
            String technologyArea);

    List<ProjectTemplate> findByCreatedByIdOrderByCreatedAtDesc(UUID createdById);
}
