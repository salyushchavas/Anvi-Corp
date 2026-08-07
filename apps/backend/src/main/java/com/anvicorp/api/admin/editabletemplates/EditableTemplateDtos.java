package com.anvicorp.api.admin.editabletemplates;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTOs for the Editable Documents admin studio (Phase 1). */
public final class EditableTemplateDtos {

    private EditableTemplateDtos() {}

    // ── List + detail row ────────────────────────────────────────────

    /**
     * Row shape used by both list + detail responses. Detail includes
     * the full {@code canonicalHtml} + {@code fieldSchema} +
     * {@code fidelityWarnings} JSON strings so the studio can re-render
     * without a second round-trip; list responses null those out to
     * keep the payload lean.
     */
    public record TemplateRow(
            UUID id,
            String key,
            String title,
            String description,
            boolean active,
            int sortOrder,
            /** True when a source DOCX has been attached; the studio
             *  should refuse to open Edit until this flips true. */
            boolean hasSource,
            UUID sourceDocumentId,
            String sourceFileName,
            Long sourceFileBytes,
            String sourceMimeType,
            /** Presigned GET URL for the source DOCX. Populated on
             *  detail responses so docx-preview can fetch the file
             *  directly from S3; null on list responses. */
            String sourceDownloadUrl,
            /** Number of fields in the saved schema (0 when the schema
             *  is null — e.g. a fresh row or after a new DOCX upload
             *  cleared it under the anchor-drift rule). */
            int fieldCount,
            /** Canonical HTML from the last save. Null on list responses
             *  and null when the template has never been saved (or was
             *  reset by a new DOCX upload). */
            String canonicalHtml,
            /** JSON-encoded field schema. Same null semantics as
             *  {@link #canonicalHtml}. */
            String fieldSchemaJson,
            /** JSON-encoded docx-preview conversion warnings from the
             *  last save. Null when no save has happened yet. */
            String fidelityWarningsJson,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record TemplateListResponse(List<TemplateRow> items) {}

    // ── Create / update / soft-delete ────────────────────────────────

    public record CreateTemplateRequest(
            /** Optional — derived from title when null. Wave 3 — capped
             *  at 50 chars + kebab-slug shape when supplied. */
            @Size(max = 50) @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$|^$",
                    message = "key must be lowercase kebab/snake shape")
            String key,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 500) String description
    ) {}

    public record UpdateTemplateMetadataRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 500) String description,
            @Min(0) @Max(1_000) Integer sortOrder
    ) {}

    // ── Source DOCX upload (presign → attach) ────────────────────────

    public record PresignUploadRequest(
            @NotBlank @Size(max = 260) String fileName,
            @NotBlank @Size(max = 120) String contentType,
            @NotNull @Min(1) @Max(25L * 1024 * 1024) Long fileSize
    ) {}

    public record PresignUploadResponse(
            String uploadUrl,
            UUID documentId,
            String storageKey,
            Instant expiresAt
    ) {}

    public record AttachSourceRequest(
            @NotNull UUID documentId
    ) {}

    // ── Save canonical HTML + schema ─────────────────────────────────

    public record SaveSchemaRequest(
            /** Full canonical HTML the studio serialised — includes the
             *  {@code <span data-field-id="…" class="doc-field">} wrappers
             *  around every selected range. Wave 3 — capped at 5 MB
             *  (comfortably above any real docx-preview render output but
             *  short of a DoS payload). Sanitized separately in
             *  {@code EditableTemplateAdminService.saveSchema} via
             *  CanonicalHtmlSanitizer. */
            @NotBlank @Size(max = 5_000_000) String canonicalHtml,
            /** Array of field entries. Server persists the JSON verbatim
             *  after a minimal structural + duplicate-name validation.
             *  Capped at 500 entries — no real template legitimately has
             *  more than a few dozen fields. */
            @NotNull @Size(max = 500) List<@NotNull FieldEntry> fields,
            /** Optional — docx-preview conversion warnings captured at
             *  render time. Persisted verbatim as
             *  {@code fidelity_warnings JSONB}. */
            @Size(max = 1000) List<@Size(max = 1000) String> fidelityWarnings
    ) {}

    /**
     * The field schema shape saved on the row. Kept intentionally
     * flexible ({@code defaultSource} is a free-form binding key like
     * {@code intern.fullName}) so Phase 2 can extend without a schema
     * migration.
     */
    public record FieldEntry(
            /** UUID string — matches the {@code data-field-id} attribute
             *  on the corresponding span in {@link
             *  SaveSchemaRequest#canonicalHtml}. */
            @NotBlank @Size(max = 100) String id,
            /** Admin-supplied human name (e.g. "Full legal name"). Must
             *  be unique across the template's fields. */
            @NotBlank @Size(max = 200) String name,
            /** {@code text | date | signature | content_block}. */
            @NotBlank
            @Pattern(regexp = "^(text|date|signature|content_block)$",
                    message = "type must be text | date | signature | content_block")
            String type,
            /** {@code ERM | INTERN | AUTO}. */
            @NotBlank
            @Pattern(regexp = "^(ERM|INTERN|AUTO)$",
                    message = "assignee must be ERM | INTERN | AUTO")
            String assignee,
            boolean required,
            /** For {@code assignee = AUTO}: which binding source Phase 2
             *  should pull the value from (e.g. {@code intern.fullName},
             *  {@code intern.employeeId}, {@code today}, {@code position}).
             *  Null otherwise. */
            @Size(max = 100) String defaultSource
    ) {}
}
