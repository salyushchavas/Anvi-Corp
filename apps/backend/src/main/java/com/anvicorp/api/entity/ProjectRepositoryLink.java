package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 1:1 link between a {@link Project} and the GitHub repository that hosts
 * its code. Owned by the company / TE for the project's entire lifecycle;
 * interns are granted collaborator access out-of-band on GitHub itself
 * (the platform doesn't call the GitHub API).
 *
 * <p>One repo per project — enforced by the UNIQUE constraint on
 * {@code project_id}.</p>
 *
 * <p>Named {@code ProjectRepositoryLink} (not {@code ProjectRepository})
 * to avoid the Spring Data naming collision with a hypothetical
 * {@code JpaRepository<Project, UUID>}-style repository.</p>
 */
@Entity
@Table(
        name = "project_repositories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_project_repositories_project",
                columnNames = "project_id"
        ),
        indexes = {
                @Index(name = "idx_pr_project", columnList = "project_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectRepositoryLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    /** Display name, e.g. {@code company-org/inventory-management-system}. */
    @Column(name = "repository_name", nullable = false, length = 200)
    private String repositoryName;

    /** Browse URL, e.g. {@code https://github.com/company-org/inventory-…}. */
    @Column(name = "repository_url", nullable = false, length = 500)
    private String repositoryUrl;

    /**
     * Numeric GitHub repository id, reserved for a future GitHub API
     * integration that mints / verifies invitations programmatically.
     * Nullable; this module never sets it.
     */
    @Column(name = "github_repository_id", length = 100)
    private String githubRepositoryId;

    /** users.id of the TE who linked the repository to the project. */
    @Column(name = "linked_by_id", nullable = false)
    private UUID linkedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
