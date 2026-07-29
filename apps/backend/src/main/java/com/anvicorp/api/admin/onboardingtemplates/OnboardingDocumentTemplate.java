package com.anvicorp.api.admin.onboardingtemplates;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-managed onboarding document template. Sits alongside the
 * {@link com.anvicorp.api.erm.documents.SkyzenDocument} static enum
 * as a data-driven overlay: bootstrap seeds one row per enum value so
 * every known template is manageable; admins can upload replacement
 * blank PDFs via the S3-backed {@link #currentDocumentId} pointer, add
 * net-new templates, and soft-deactivate ones that aren't in use
 * anymore (soft-delete keeps in-progress packets intact).
 *
 * <p>Intern download resolution — the backend intern-packet DTO now
 * includes a per-task {@code templateDownloadUrl} field which is
 * either a presigned S3 URL (when the admin has uploaded a file for
 * this template) or the legacy static asset URL from the enum
 * (fallback for un-replaced templates).</p>
 */
@Entity
@Table(name = "onboarding_document_templates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_onboarding_document_templates_key",
                columnNames = "key_"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingDocumentTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    /** Key identifier — matches the SkyzenDocument enum name for seeded
     *  rows (W4_2026, I9_FORM_2026, etc.); free-form for admin-added
     *  rows. Used as the intern download endpoint's path parameter. */
    @Column(name = "key_", length = 100, nullable = false)
    private String key;

    @Column(nullable = false, length = 255)
    private String title;

    /** TAX / IMMIGRATION / EMPLOYMENT / LEGAL / INFORMATIONAL / OTHER. */
    @Column(nullable = false, length = 40)
    private String category;

    /** GENERAL / FINANCIAL / GOVERNMENT_ID / PII. */
    @Column(nullable = false, length = 40)
    private String sensitivity;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Currently-served blank file — a {@link com.anvicorp.api.entity.Document}
     *  id. Null when the template is served from the legacy static
     *  Next.js asset (enum {@code publicUrl()} fallback). */
    @Column(name = "current_document_id")
    private UUID currentDocumentId;

    /** Deactivated templates are hidden from admin lists + intern
     *  download endpoints; historical packets still resolve to whatever
     *  file was current at task-creation time via the standing
     *  {@link com.anvicorp.api.entity.Document} row (soft-delete
     *  preserves the file). */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 100;

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
