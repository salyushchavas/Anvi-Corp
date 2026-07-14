package com.anvicorp.api.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One file in the intern's editable workspace for a {@link
 * com.anvicorp.api.entity.Project}. Edited freely while the project is
 * {@code IN_PROGRESS}; locked (no upserts/deletes) while the project is
 * {@code SUBMITTED}; re-opened on a {@code RETURNED} bounce.
 *
 * <p>The {@code (projectId, path)} pair is unique — a file's identity is its
 * path within the workspace, not its row id. The id exists so renames are a
 * single UPDATE rather than DELETE+INSERT.</p>
 *
 * <p>Pure POJO + JPA only — no Spring annotations. Storage is the database;
 * no filesystem or object-store coupling.</p>
 */
@Entity
@Table(
        name = "project_workspace_files",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_pwf_project_path",
                columnNames = {"project_id", "path"}),
        indexes = {
                @Index(name = "idx_pwf_project", columnList = "project_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectWorkspaceFile {

    @Id
    @GeneratedValue
    private UUID id;

    /** Project this file belongs to. Plain UUID — no JPA mapping, matches the codebase pattern for FK-as-id. */
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /**
     * Relative path within the workspace, e.g. {@code src/main.py}. Validation
     * (no {@code ..}, no leading slash, restricted character set) lives in
     * {@code WorkspaceFileService} so the entity stays a pure data carrier.
     * 512 chars covers deeply-nested templates without being unreasonable.
     */
    @Column(nullable = false, length = 512)
    private String path;

    /**
     * UTF-8 file content. {@code TEXT} so the column can hold up to the
     * 1-MB-per-file cap enforced by the service. Binary files are not
     * supported in this build.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Cached size in bytes so the per-workspace 10 MB cap is a single sum. */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @UpdateTimestamp
    @Column(name = "last_modified_at", nullable = false)
    private Instant lastModifiedAt;

    /** User id (intern) who last touched this file. */
    @Column(name = "last_modified_by")
    private UUID lastModifiedBy;
}
