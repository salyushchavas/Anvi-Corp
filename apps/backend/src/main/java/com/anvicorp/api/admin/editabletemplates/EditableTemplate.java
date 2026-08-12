package com.anvicorp.api.admin.editabletemplates;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Editable Documents — Phase 1: the admin template row.
 *
 * <p>An admin uploads a DOCX, the studio renders it faithfully via
 * docx-preview in the browser, then the admin selects text ranges to
 * turn into fields (per-field owner + type). Save persists the canonical
 * HTML the studio produced plus the field schema JSON — Phase 2 will
 * consume both to instantiate and fill per-intern rows.</p>
 *
 * <h2>Why canonical HTML lives in Postgres</h2>
 * The survey called out the Vercel / Railway split — the browser can't
 * hand rendered HTML back to a fresh session by reference. The full
 * canonical HTML must be persisted server-side so any future re-open of
 * the studio (or the Phase-2 instantiation path) gets the exact same
 * anchored spans without re-running the DOCX conversion (which would
 * drift the {@code data-field-id} anchors).
 *
 * <h2>Anchor-drift rule</h2>
 * When the admin uploads a NEW DOCX version (see
 * {@code EditableTemplateAdminService.attachSourceFile}), both
 * {@link #canonicalHtml} and {@link #fieldSchemaJson} are reset to
 * {@code null} so the admin re-selects fields on the fresh render.
 * There is no silent auto-migration — the survey flagged this
 * explicitly and the service enforces it.
 */
@Entity
@Table(name = "editable_templates", indexes = {
        @Index(name = "uk_editable_templates_key", columnList = "key_", unique = true),
        @Index(name = "idx_editable_templates_active_sort",
                columnList = "active, sort_order, title")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EditableTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    /** Stable identifier used in URLs (e.g. {@code OFFER_LETTER_V2}). */
    @Column(name = "key_", nullable = false, length = 100)
    private String key;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 500;

    /** Admin who created the row (never overwritten on subsequent edits). */
    @Column(name = "created_by_id", nullable = false)
    private UUID createdById;

    /**
     * The DOCX Document row this template was built from. Nullable while
     * the admin is between "created the row" and "uploaded the source DOCX";
     * Phase 2 must reject any instantiation attempt for a template with a
     * null source.
     */
    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    /**
     * Canonical HTML the studio serialised at save time (docx-preview
     * output mutated with {@code <span data-field-id="…" class="doc-field">}
     * wrappers around each selected range). Null until the admin saves for
     * the first time; reset to null when a new DOCX version is attached
     * (anchor-drift rule).
     */
    @Column(name = "canonical_html", columnDefinition = "TEXT")
    private String canonicalHtml;

    /**
     * JSONB — the field schema saved alongside {@link #canonicalHtml}.
     * Shape: an array of {@code {id, name, type, assignee, anchor:{spanId},
     * required, defaultSource}} entries. Null until first save; reset to
     * null on new DOCX version.
     */
    @Column(name = "field_schema", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String fieldSchemaJson;

    /**
     * JSONB — docx-preview conversion warnings captured at save time
     * (e.g. dropped features, unsupported constructs). Displayed as a
     * fidelity banner on re-open so the admin can review before publishing.
     */
    @Column(name = "fidelity_warnings", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String fidelityWarningsJson;

    /**
     * JSONB — structured formatting profile extracted from the source
     * DOCX at upload time by {@link DocxFormattingExtractor}. Captures
     * the authoritative source data ({@code sectPr} page geometry,
     * {@code header{N}.xml} / {@code footer{N}.xml} paragraph
     * properties INCLUDING negative indents that docx-preview drops,
     * body-default typography, numbering definitions) so consumers
     * (studio prep, PDF renderer) render data-driven off the source
     * instead of scraping docx-preview's lossy HTML.
     *
     * <p>Serialised shape mirrors {@link FormattingProfile} (see that
     * record's javadoc for the schema). Includes a top-level
     * {@code "version"} field; consumers MUST tolerate a newer version
     * defensively.</p>
     *
     * <p>Null when: extraction failed (upload succeeds regardless — the
     * profile is strictly additive enrichment), OR the template pre-
     * dates the metadata layer (legacy row) and hasn't had a new DOCX
     * attached since. Consumers MUST fall back to the existing scrape-
     * from-HTML path when this is null.</p>
     *
     * <p>Overwritten on every source attach — the profile is tied to
     * the currently-attached DOCX. Not part of the anchor-drift reset
     * (fields cleared on re-upload); the extractor runs fresh on the
     * new bytes and stores whatever it captures.</p>
     */
    @Column(name = "source_formatting_profile", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String sourceFormattingProfileJson;

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
