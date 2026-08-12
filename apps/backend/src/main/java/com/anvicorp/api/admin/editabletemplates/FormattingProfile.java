package com.anvicorp.api.admin.editabletemplates;

import java.util.List;
import java.util.Map;

/**
 * Structured formatting profile extracted from a template's source DOCX by
 * {@link DocxFormattingExtractor} on upload and persisted on the
 * {@link EditableTemplate} row as JSONB.
 *
 * <p><b>Why this exists.</b> The IDMS pipeline previously relied on the
 * frontend {@code docx-preview} library to convert DOCX → HTML, and then
 * treated that HTML as the source of truth for formatting. {@code
 * docx-preview}'s conversion is LOSSY — it drops (or mangles) the
 * source document's real formatting in ways that produced every
 * font / bullet / margin / header bug we've hit: it strips negative
 * paragraph indents (a header with {@code w:ind w:left="-1587"} hanging
 * into the left margin loses that indent and renders at 0), it doesn't
 * put font declarations on list {@code <p>} tags (so bullet items
 * cascade to body defaults instead of the run's real font), and it
 * emits page containers with hardcoded widths that fight the PDF
 * page's own margins.</p>
 *
 * <p>The authoritative data lives in the DOCX's XML — {@code
 * document.xml}, {@code header{N}.xml}, {@code footer{N}.xml},
 * {@code styles.xml}, {@code numbering.xml}, and the section-properties
 * {@code sectPr}. This profile captures the minimum-useful subset of
 * that XML in a shape consumers (studio prep, PDF renderer, future
 * edge-case fallbacks) can read without re-parsing the source DOCX or
 * trusting docx-preview's HTML.</p>
 *
 * <h2>Stage 1 scope (this record)</h2>
 * Page geometry (size + margins), header / footer paragraph properties
 * (alignment + indent + spacing + runs), body default typography, and
 * list definitions. Explicitly deferred to Stage 2+ as consumers
 * demand them: per-body-paragraph properties, per-run inline overrides
 * beyond header/footer, colors on runs, tables, borders, tab stops,
 * images, comments, tracked changes.
 *
 * <h2>Versioning</h2>
 * The top-level {@link #version} field is stamped by the extractor.
 * Consumers MUST tolerate a null or newer version defensively — a
 * template whose profile was captured under an older version stays
 * usable; a template whose profile is null (extraction failed or
 * legacy template pre-dating the layer) falls back to the current
 * scrape-from-HTML rendering path.
 *
 * <h2>Units</h2>
 * DOCX stores lengths in twips (1/1440 inch, 1/20 point). The
 * extractor pre-computes BOTH {@code inches} and {@code points} for
 * every length so consumers skip the arithmetic and get the CSS unit
 * they need directly. Raw twips are also carried for the pedantic
 * consumer.
 */
public record FormattingProfile(
        /** Bumped when the profile schema changes in a way consumers
         *  need to detect. Stage 1 = 1. */
        int version,
        PageGeometry page,
        /** First / active header for the primary section, or {@code null}
         *  when the source has no header. Stage 1 captures the first
         *  section only; multi-section templates land in Stage 2. */
        HeaderFooterProfile header,
        /** First / active footer for the primary section, or {@code null}. */
        HeaderFooterProfile footer,
        /** Document / body default typography — the cascade root every
         *  unstyled paragraph inherits from. */
        BodyDefault bodyDefault,
        /** Every numbering definition present in {@code numbering.xml}
         *  keyed by its numId. Empty map when the document uses no
         *  lists. Consumers rendering a list item look up the item's
         *  numId + ilvl to get the real bullet character + indent. */
        Map<String, ListDefinition> lists
) {

    /** Page-level geometry. Pre-computed in inches, points, AND raw twips
     *  so consumers pick whatever CSS unit they render with. */
    public record PageGeometry(
            Length widthIn, Length heightIn,
            /** {@code "portrait"} or {@code "landscape"}. Nullable if
             *  the source didn't specify (rare — Word always writes it). */
            String orientation,
            Margins margins
    ) {}

    /** Page margins from {@code sectPr/pgMar}. Word writes negative
     *  margins for edge-bleeding layouts; the extractor preserves
     *  the sign so the profile faithfully reflects the source. */
    public record Margins(
            Length top, Length right, Length bottom, Length left,
            /** {@code header} = distance from page top to header body;
             *  {@code footer} = distance from page bottom to footer body. */
            Length headerDistance, Length footerDistance,
            Length gutter
    ) {}

    /** Header or footer profile. Contains the ordered list of paragraphs
     *  the extractor read from the ACTIVE header/footer file in the first
     *  section (headers can be default / first-page / even-page; the
     *  extractor picks the default one — Stage 2 can add the others).
     */
    public record HeaderFooterProfile(
            /** Which of the docx-preview {@code <header>} / {@code <footer>}
             *  variants this profile corresponds to. Stage 1 always
             *  {@code "default"}; the enum values are documented for
             *  Stage 2 (first-page / even-page). */
            String type,
            List<ParagraphProfile> paragraphs
    ) {}

    /** Per-paragraph properties resolved from paragraph + linked style +
     *  document defaults. NULL fields mean "not specified at this level"
     *  — the consumer inherits from whatever the parent context provides.
     */
    public record ParagraphProfile(
            /** Linked style ID, e.g. {@code "Normal"}, {@code "Heading1"}. */
            String styleId,
            /** {@code left | right | center | both | distribute} — DOCX
             *  {@code w:jc} value; null if unspecified. */
            String alignment,
            Indent indent,
            Spacing spacing,
            /** True if this paragraph is a list item (has {@code numPr}). */
            boolean listItem,
            /** Numbering definition ID (references {@link #lists} key) when
             *  {@link #listItem} is true. */
            String numId,
            /** List level (0-based) when {@link #listItem} is true. */
            Integer numLevel,
            /** Concatenated text of every run in this paragraph. Useful
             *  for consumers matching a header paragraph by its content
             *  (e.g. "the company-address header line"). */
            String text,
            /** Individual runs in document order. */
            List<RunProfile> runs
    ) {}

    /** Paragraph indent from {@code w:ind}. Negative values are legal
     *  and MUST be preserved verbatim — this is precisely what
     *  docx-preview drops and what causes header-alignment bugs. */
    public record Indent(
            Length left, Length right, Length firstLine, Length hanging
    ) {}

    /** Paragraph spacing from {@code w:spacing}. */
    public record Spacing(
            /** Space before the paragraph. */
            Length before,
            /** Space after the paragraph. */
            Length after,
            /** Between lines within the paragraph. */
            Length line,
            /** {@code auto | atLeast | exact} — Word's rule for
             *  interpreting {@link #line}. */
            String lineRule
    ) {}

    /** A single text run within a paragraph. */
    public record RunProfile(
            String text,
            /** Font family, e.g. {@code "Calibri"}, {@code "Times New Roman"}. */
            String fontFamily,
            /** Font size in half-points as DOCX stores it; pre-converted
             *  to points in {@link #fontSizePt}. */
            Integer fontSizeHalfPt,
            Double fontSizePt,
            Boolean bold,
            Boolean italic,
            /** RGB hex without leading {@code #}, e.g. {@code "000000"}. */
            String color
    ) {}

    /** Body-level cascade root — the properties every paragraph inherits
     *  unless overridden by its own paragraph / style / run properties. */
    public record BodyDefault(
            String fontFamily,
            Double fontSizePt,
            String alignment
    ) {}

    /** A numbering definition from {@code numbering.xml}. */
    public record ListDefinition(
            /** The abstract numId this concrete definition references. */
            String abstractNumId,
            /** Level 0 = top-level bullets; level 1 = nested; etc. */
            List<LevelDefinition> levels
    ) {}

    public record LevelDefinition(
            int levelIndex,
            /** {@code bullet | decimal | lowerLetter | upperLetter |
             *   lowerRoman | upperRoman | ...} — the DOCX
             *  {@code w:numFmt val}. */
            String format,
            /** The bullet character or number template ({@code "•"},
             *  {@code "%1."}, {@code "%1)"}). */
            String text,
            Length indentLeft,
            Length indentFirstLine
    ) {}

    /**
     * A DOCX length pre-computed in three units so consumers don't
     * re-do the arithmetic. Twips is the source unit (1/1440 inch,
     * 1/20 point). Consumers should prefer {@link #inches} for CSS
     * inches, {@link #points} for CSS pt, and use {@link #twips} only
     * when they need bit-exact round-trips back to DOCX XML.
     */
    public record Length(long twips, double inches, double points) {

        /** Convenience — construct a Length from a raw twips value.
         *  The two derived units are computed from twips so the three
         *  are always consistent. */
        public static Length fromTwips(long twips) {
            return new Length(twips, twips / 1440.0, twips / 20.0);
        }
    }
}
