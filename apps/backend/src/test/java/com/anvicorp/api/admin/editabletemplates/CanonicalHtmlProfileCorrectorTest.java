package com.anvicorp.api.admin.editabletemplates;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 2 regression suite for {@link CanonicalHtmlProfileCorrector}.
 *
 * <p>Two flagship tests prove the generic data-driven header positioning:
 * one for the ANVI-shape NEGATIVE indent (proves the rejected "force
 * left" band-aid is replaced with a data-driven fix), one for a CENTERED
 * header (proves the same code path handles a completely different
 * layout without any hardcoding). Additional tests pin: null-profile
 * fallback (legacy templates), null-header safety, section-padding
 * authority, list-item font-family injection, and full null-profile
 * backward compatibility.</p>
 */
class CanonicalHtmlProfileCorrectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalHtmlProfileCorrector corrector =
            new CanonicalHtmlProfileCorrector(mapper);

    // ── Consumer 1 — header alignment / indent ───────────────────────

    /**
     * ⭐ FLAGSHIP TEST — proves the generic header fix.
     *
     * <p>The ANVI offer-letter shape: a header with
     * {@code w:ind w:left="-1587"} — a negative left indent that
     * pulls the logo into the left margin. docx-preview drops the
     * indent; the profile captures it; this corrector applies it as
     * inline {@code margin-left} on the {@code <header>} element.
     * The rejected "force-left" hardcode is replaced with data-driven
     * positioning that works for ANY document.</p>
     *
     * <p>Arithmetic: -1587 twips ÷ 1440 twips-per-inch = -1.10in
     * (a substantial hang-off-the-left-margin, exactly what the
     * source DOCX author configured).</p>
     */
    @Test
    void applies_negative_header_indent_from_profile_generically() throws Exception {
        // ANVI-shape profile — the only bit that matters is the
        // header's -1587 twips indent (Stage 1 test proves POI reads
        // this correctly; this test proves Stage 2 applies it back
        // to the HTML).
        String profileJson = mapper.writeValueAsString(minimalProfile(
                headerParagraph("left", -1587L)));
        String html = "<header><p>Company logo goes here</p></header>"
                + "<section class=\"docx\"><p>Body</p></section>";

        String out = corrector.correct(html, profileJson);

        assertNotNull(out);
        assertTrue(out.contains("<header"), "header preserved: " + out);
        assertTrue(out.contains("style=\""), "header now has inline style");
        assertTrue(out.contains("margin-left:-1.10in"),
                "negative margin-left (-1.10in from -1587 twips) must be applied "
                        + "— the whole point of Stage 2 replacing the force-left "
                        + "band-aid: " + out);
        assertTrue(out.contains("text-align:left"),
                "alignment (left) applied: " + out);
    }

    /**
     * ⭐ FLAGSHIP TEST 2 — proves the same code path handles a
     * completely different header layout without any change. A
     * document with a CENTERED corporate letterhead gets
     * {@code text-align: center}. Zero hardcoding.
     */
    @Test
    void applies_centered_header_alignment_from_profile() throws Exception {
        String profileJson = mapper.writeValueAsString(minimalProfile(
                headerParagraph("center", 0L)));
        String html = "<header><p>Centered letterhead</p></header>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("text-align:center"),
                "centered header must get text-align:center — proves the "
                        + "corrector isn't hardcoded to 'left': " + out);
    }

    /** Right-aligned header (e.g. a page-number-only header) —
     *  another point on the generic curve. */
    @Test
    void applies_right_aligned_header_from_profile() throws Exception {
        String profileJson = mapper.writeValueAsString(minimalProfile(
                headerParagraph("right", 0L)));
        String html = "<header><p>Page 1</p></header>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("text-align:right"), out);
    }

    /** DOCX {@code w:jc="both"} means CSS {@code text-align: justify}
     *  (a legitimate paragraph justification, not the same as center). */
    @Test
    void translates_docx_both_to_css_justify() throws Exception {
        String profileJson = mapper.writeValueAsString(minimalProfile(
                headerParagraph("both", 0L)));
        String html = "<header><p>Justified header</p></header>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("text-align:justify"),
                "DOCX 'both' should translate to CSS 'justify': " + out);
    }

    /** Null profile — legacy templates from before Stage 1. HTML MUST
     *  be returned unchanged so nothing regresses. */
    @Test
    void null_profile_returns_html_unchanged() {
        String html = "<header><p>Old-shape template</p></header>";
        assertEquals(html, corrector.correct(html, null));
        assertEquals(html, corrector.correct(html, ""));
        assertEquals(html, corrector.correct(html, "   "));
    }

    /** Malformed JSON — same fail-open contract. */
    @Test
    void malformed_profile_json_returns_html_unchanged() {
        String html = "<header><p>hi</p></header>";
        String garbage = "{ not json {";
        assertEquals(html, corrector.correct(html, garbage),
                "malformed JSON must not throw or corrupt HTML");
    }

    /** Profile present but no header — HTML's header (if any) must be
     *  left alone; the corrector must not clobber existing styles. */
    @Test
    void profile_without_header_leaves_html_header_untouched() throws Exception {
        String profileJson = mapper.writeValueAsString(minimalProfile(null));
        String html = "<header style=\"color:red;\"><p>hi</p></header>";
        String out = corrector.correct(html, profileJson);
        assertTrue(out.contains("color:red"),
                "existing header style must survive when profile has no header: " + out);
        assertFalse(out.contains("text-align"),
                "no text-align added when profile provides none: " + out);
        assertFalse(out.contains("margin-left"),
                "no margin-left added when profile provides none: " + out);
    }

    /** HTML with no header at all — no crash, no accidental injection. */
    @Test
    void html_without_header_is_passthrough_for_header_step() throws Exception {
        String profileJson = mapper.writeValueAsString(minimalProfile(
                headerParagraph("center", 0L)));
        String html = "<section class=\"docx\"><p>Body only</p></section>";
        String out = corrector.correct(html, profileJson);
        assertFalse(out.contains("<header"),
                "no header created — corrector only patches existing headers: " + out);
    }

    // ── Consumer 2 — authoritative page geometry ─────────────────────

    /**
     * Profile page margins should be written onto the first
     * {@code section.docx} inline style so the PDF renderer's
     * existing scrape reads authoritative values. Letter page
     * (8.5×11) with 1-inch margins.
     */
    @Test
    void writes_authoritative_page_margins_onto_section() throws Exception {
        FormattingProfile.PageGeometry page = new FormattingProfile.PageGeometry(
                FormattingProfile.Length.fromTwips(12240),  // 8.5 in
                FormattingProfile.Length.fromTwips(15840),  // 11 in
                "portrait",
                new FormattingProfile.Margins(
                        FormattingProfile.Length.fromTwips(1440), // top
                        FormattingProfile.Length.fromTwips(1440), // right
                        FormattingProfile.Length.fromTwips(1440), // bottom
                        FormattingProfile.Length.fromTwips(1440), // left
                        FormattingProfile.Length.fromTwips(720),  // header dist
                        FormattingProfile.Length.fromTwips(720),  // footer dist
                        null));
        FormattingProfile profile = new FormattingProfile(
                1, page, null, null, new FormattingProfile.BodyDefault(null, null, null),
                Map.of());
        String profileJson = mapper.writeValueAsString(profile);
        String html = "<section class=\"docx\" style=\"padding-top:2in;width:9in;\">"
                + "<p>Body</p></section>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("padding-top:1.00in"),
                "profile's 1-inch top margin should overwrite the 2in from HTML: " + out);
        assertTrue(out.contains("padding-left:1.00in"), out);
        assertTrue(out.contains("padding-right:1.00in"), out);
        assertTrue(out.contains("padding-bottom:1.00in"), out);
        assertTrue(out.contains("width:8.50in"),
                "profile's 8.5in width should overwrite the 9in from HTML: " + out);
        assertTrue(out.contains("min-height:11.00in"), out);
    }

    /** Null page geometry — no changes to section styling. */
    @Test
    void null_page_geometry_leaves_section_unchanged() throws Exception {
        FormattingProfile profile = new FormattingProfile(
                1, null, null, null,
                new FormattingProfile.BodyDefault(null, null, null), Map.of());
        String profileJson = mapper.writeValueAsString(profile);
        String html = "<section class=\"docx\" style=\"padding-top:2in;\">"
                + "<p>x</p></section>";
        String out = corrector.correct(html, profileJson);
        assertTrue(out.contains("padding-top:2in"),
                "existing padding must survive when profile has no page geometry: " + out);
    }

    // ── Consumer 3 — list-item font ──────────────────────────────────

    /**
     * List items ({@code <p class="docx-num-...">}) missing an inline
     * font-family should get the body-default font from the profile
     * injected so bullets render in the correct font instead of the
     * browser default.
     */
    @Test
    void injects_body_default_font_into_list_items() throws Exception {
        FormattingProfile profile = new FormattingProfile(
                1,
                new FormattingProfile.PageGeometry(null, null, null,
                        new FormattingProfile.Margins(
                                null, null, null, null, null, null, null)),
                null, null,
                new FormattingProfile.BodyDefault("Calibri", 11.0, "left"),
                Map.of());
        String profileJson = mapper.writeValueAsString(profile);
        String html = "<p class=\"docx-num-3-0\">Bullet one</p>"
                + "<p class=\"docx-num-3-0\">Bullet two</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("font-family:Calibri"),
                "body-default Calibri must be injected onto list <p>: " + out);
        // Both bullets should get it — count occurrences.
        int count = out.split("font-family:Calibri").length - 1;
        assertEquals(2, count, "font must be injected on each list <p>");
    }

    /** An existing inline font-family on a list <p> must NOT be
     *  clobbered by the body default — the paragraph's own font wins. */
    @Test
    void does_not_override_existing_list_item_font_family() throws Exception {
        FormattingProfile profile = new FormattingProfile(
                1, null, null, null,
                new FormattingProfile.BodyDefault("Calibri", 11.0, null),
                Map.of());
        String profileJson = mapper.writeValueAsString(profile);
        String html = "<p class=\"docx-num-1-0\" style=\"font-family:Arial;\">Custom</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("font-family:Arial"),
                "existing Arial must survive — corrector only ADDS, never overrides: " + out);
        assertFalse(out.contains("font-family:Calibri"),
                "Calibri must NOT be injected when Arial is already there: " + out);
    }

    // ── Backward compatibility ───────────────────────────────────────

    /**
     * Full backward-compat pin. A legacy template (null profile,
     * pre-Stage-1) must have byte-identical HTML output. Regression
     * protection for the "does Stage 2 accidentally change legacy
     * templates?" concern.
     */
    @Test
    void legacy_null_profile_is_completely_backward_compatible() {
        String legacyHtml =
                "<header style=\"padding:0;\"><p>Old logo</p></header>"
                        + "<section class=\"docx\" style=\"padding:1in;width:8.5in;\">"
                        + "<p class=\"docx-num-1-0\">Legacy bullet</p>"
                        + "<p>Body text</p>"
                        + "</section>"
                        + "<footer><p>Old address</p></footer>";
        String out = corrector.correct(legacyHtml, null);
        assertEquals(legacyHtml, out, "null profile MUST return HTML byte-identical");
    }

    /** Empty HTML — safe pass-through regardless of profile. */
    @Test
    void empty_html_returns_input_unchanged() throws Exception {
        String profileJson = mapper.writeValueAsString(minimalProfile(
                headerParagraph("center", 0L)));
        assertEquals("", corrector.correct("", profileJson));
        assertEquals(null, corrector.correct(null, profileJson));
    }

    // ── Fixture builders ─────────────────────────────────────────────

    /** Build a minimal well-formed profile. Header may be null. */
    private FormattingProfile minimalProfile(
            FormattingProfile.HeaderFooterProfile header) {
        return new FormattingProfile(
                1,
                new FormattingProfile.PageGeometry(null, null, null,
                        new FormattingProfile.Margins(
                                null, null, null, null, null, null, null)),
                header,
                null,
                new FormattingProfile.BodyDefault(null, null, null),
                Map.of());
    }

    /** Build a single-paragraph header with the given alignment +
     *  raw twips left indent. Other properties are null. */
    private FormattingProfile.HeaderFooterProfile headerParagraph(
            String alignment, long leftIndentTwips) {
        FormattingProfile.Length leftIndent = leftIndentTwips == 0L
                ? null
                : FormattingProfile.Length.fromTwips(leftIndentTwips);
        FormattingProfile.Indent indent = new FormattingProfile.Indent(
                leftIndent, null, null, null);
        FormattingProfile.Spacing spacing = new FormattingProfile.Spacing(
                null, null, null, null);
        FormattingProfile.ParagraphProfile p = new FormattingProfile.ParagraphProfile(
                null, alignment, indent, spacing, false, null, null,
                "header text", List.of());
        return new FormattingProfile.HeaderFooterProfile("default", List.of(p));
    }
}
