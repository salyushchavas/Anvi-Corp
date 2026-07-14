package com.anvicorp.api.workspace.infrastructure;

import com.anvicorp.api.workspace.domain.ProjectWorkspaceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectWorkspaceFileRepository
        extends JpaRepository<ProjectWorkspaceFile, UUID> {

    /** All files for one project. Ordered by path so the file tree is stable. */
    List<ProjectWorkspaceFile> findByProjectIdOrderByPathAsc(UUID projectId);

    Optional<ProjectWorkspaceFile> findByProjectIdAndPath(UUID projectId, String path);

    boolean existsByProjectIdAndPath(UUID projectId, String path);

    long countByProjectId(UUID projectId);

    /** Sum of size_bytes for the per-workspace 10 MB guard. Returns 0 when empty. */
    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM ProjectWorkspaceFile f "
            + "WHERE f.projectId = :projectId")
    long sumSizeBytesByProjectId(@Param("projectId") UUID projectId);
}
