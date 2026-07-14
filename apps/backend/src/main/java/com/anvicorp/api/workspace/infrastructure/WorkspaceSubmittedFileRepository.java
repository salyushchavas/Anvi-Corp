package com.anvicorp.api.workspace.infrastructure;

import com.anvicorp.api.workspace.domain.WorkspaceSubmittedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceSubmittedFileRepository
        extends JpaRepository<WorkspaceSubmittedFile, UUID> {

    List<WorkspaceSubmittedFile> findBySubmissionIdOrderByPathAsc(UUID submissionId);

    Optional<WorkspaceSubmittedFile> findBySubmissionIdAndPath(UUID submissionId, String path);
}
