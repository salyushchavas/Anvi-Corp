package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Trainer Phase 0 — the doc's TrainingMaterial / Files-Templates module.
 * Reusable project blueprint that any TE can instantiate into a fresh
 * {@link Project} via the doc §7 assignment wizard. Shared library —
 * no per-trainer isolation per Trainer doc §3 Reporting row (cross-
 * trainer collaboration implied).
 */
@Entity
@Table(name = "project_templates", indexes = {
        @Index(name = "idx_project_templates_tech",
                columnList = "technology_area, published, archived_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "technology_area", nullable = false, length = 100)
    private String technologyArea;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Doc §7 "Instructions" — rich text / Markdown. */
    @Column(name = "instructions_md", nullable = false, columnDefinition = "TEXT")
    private String instructionsMd;

    /** Doc §7 "GitHub instructions" — conditional. */
    @Column(name = "github_instructions_md", columnDefinition = "TEXT")
    private String githubInstructionsMd;

    @Column(name = "learning_objective_label", length = 300)
    private String learningObjectiveLabel;

    /** JSON array of document UUIDs — resolved via DocumentService.
     *  Stored as Postgres JSONB ({@code SchemaFixupRunner} creates the column
     *  with type {@code JSONB}); the Java side keeps it as a raw JSON string
     *  so the existing service callers (which pass {@code "[]"} on create
     *  and edit the array via JSON-text manipulation) don't have to change.
     *  Without {@link JdbcTypeCode#value()} = {@link SqlTypes#JSON} Hibernate
     *  binds {@code String} as {@code varchar} and pgjdbc rejects the
     *  {@code character varying -> jsonb} cast with SQLGrammarException —
     *  the root cause behind the "New Template" + "Assign Project (template
     *  instantiation)" 500s. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attached_document_ids", columnDefinition = "jsonb")
    private String attachedDocumentIds;

    @Column(name = "created_by_id", nullable = false)
    private UUID createdById;

    @Column(name = "published", nullable = false)
    @Builder.Default
    private Boolean published = Boolean.FALSE;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private Integer usageCount = 0;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
