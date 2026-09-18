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

    // ── Consumer 4 — list hanging indent (Stage 3) ───────────────────

    /**
     * ⭐ FLAGSHIP STAGE-3 TEST — proves the corrector now consumes the
     * numbering geometry it has been extracting and discarding since
     * Stage 1.
     *
     * <p>The ANVI duty list is {@code w:ind w:left="1080" w:hanging="360"}.
     * Stage 1 normalises the hanging into a negative first-line indent,
     * so the profile carries {@code left=+1080tw}, {@code
     * firstLine=-360tw}. Those map onto the CSS hanging-indent pair:</p>
     *
     * <pre>
     *   margin-left:  1080tw / 1440 =  0.75in   ← wrapped lines land here
     *   text-indent:  -360tw / 1440 = -0.25in   ← marker pulled back to 0.50in
     * </pre>
     *
     * <p>Before this consumer existed the {@code <p>} came out of the
     * corrector with no horizontal geometry at all, which is why bullets
     * sat flush on the left margin and wrapped lines slid underneath
     * their own bullet.</p>
     */
    @Test
    void applies_list_hanging_indent_from_profile() throws Exception {
        String profileJson = mapper.writeValueAsString(
                listProfile("40", 1080L, 360L, null, null));
        String html = "<section class=\"docx\">"
                + "<p class=\"docx-num-40-0\">Collect, clean, preprocess and "
                + "analyze structured and unstructured data for use in AI/ML "
                + "applications.</p></section>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("margin-left:0.75in"),
                "left indent 1080tw must become margin-left:0.75in — this is "
                        + "what makes wrapped lines align under the TEXT: " + out);
        assertTrue(out.contains("text-indent:-0.25in"),
                "hanging 360tw must become text-indent:-0.25in — this is what "
                        + "pulls the bullet marker back out to 0.50in: " + out);
    }

    /**
     * The corrective values come from the profile, NOT from a constant.
     * A template with a completely different list indent must get its
     * own numbers — same guarantee the header consumer makes.
     */
    @Test
    void list_indent_is_per_template_never_hardcoded() throws Exception {
        // 2160tw = 1.50in left, 720tw hanging = -0.50in first line.
        String profileJson = mapper.writeValueAsString(
                listProfile("7", 2160L, 720L, null, null));
        String html = "<p class=\"docx-num-7-0\">Deeply indented item</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("margin-left:1.50in"),
                "must use THIS template's 2160tw, not the ANVI 1080tw: " + out);
        assertTrue(out.contains("text-indent:-0.50in"), out);
        assertFalse(out.contains("0.75in"),
                "no leakage of the other template's geometry: " + out);
    }

    /**
     * Every item carries the numId class, so every item gets the
     * indent — including the FIRST duty, which is the one that reads as
     * an un-bulleted paragraph in the broken preview. No special-casing:
     * the uniform pass covers it because it is an ordinary list item.
     */
    @Test
    void all_list_items_including_the_first_get_the_same_indent() throws Exception {
        String profileJson = mapper.writeValueAsString(
                listProfile("40", 1080L, 360L, null, null));
        String html = "<section class=\"docx\">"
                + "<p class=\"docx-num-40-0\">Design, develop, and implement "
                + "machine learning and artificial intelligence models.</p>"
                + "<p class=\"docx-num-40-0\">Collect, clean, preprocess.</p>"
                + "<p class=\"docx-num-40-0\">Develop, train, test, validate.</p>"
                + "</section>";

        String out = corrector.correct(html, profileJson);

        assertEquals(3, out.split("margin-left:0.75in").length - 1,
                "all three items must be indented — the first duty is not a "
                        + "special case, it is just a list item: " + out);
        assertEquals(3, out.split("text-indent:-0.25in").length - 1, out);
    }

    /**
     * Unlike the font pass (which only fills gaps), the indent pass is
     * AUTHORITATIVE: whatever docx-preview inferred is exactly the value
     * we know to be wrong, so the profile must overwrite it rather than
     * defer to it.
     */
    @Test
    void overwrites_the_indent_docx_preview_inferred() throws Exception {
        String profileJson = mapper.writeValueAsString(
                listProfile("40", 1080L, 360L, null, null));
        String html = "<p class=\"docx-num-40-0\" "
                + "style=\"margin-left:0in;text-indent:0in;color:navy;\">Item</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("margin-left:0.75in"),
                "profile must win over docx-preview's 0in: " + out);
        assertTrue(out.contains("text-indent:-0.25in"), out);
        assertFalse(out.contains("margin-left:0in"),
                "the wrong inferred value must be stripped, not stacked: " + out);
        assertTrue(out.contains("color:navy"),
                "unrelated declarations must survive the rewrite: " + out);
    }

    /** A padding-left expressing the same offset would stack on top of
     *  the margin we set, so it is stripped along with the rest. */
    @Test
    void strips_padding_left_so_indents_do_not_stack() throws Exception {
        String profileJson = mapper.writeValueAsString(
                listProfile("40", 1080L, 360L, null, null));
        String html = "<p class=\"docx-num-40-0\" style=\"padding-left:0.5in;\">Item</p>";

        String out = corrector.correct(html, profileJson);

        assertFalse(out.contains("padding-left"),
                "padding-left must not survive alongside the new margin-left: " + out);
        assertTrue(out.contains("margin-left:0.75in"), out);
    }

    /** A level with no {@code w:ind} at all — nothing to apply, so the
     *  element must be left exactly as docx-preview emitted it. */
    @Test
    void level_without_indent_leaves_item_untouched() throws Exception {
        FormattingProfile.LevelDefinition level =
                new FormattingProfile.LevelDefinition(0, "bullet", "•", null, null);
        FormattingProfile profile = new FormattingProfile(
                2, null, null, null,
                new FormattingProfile.BodyDefault(null, null, null),
                Map.of("40", new FormattingProfile.ListDefinition(
                        "0", List.of(level), null, null)));
        String html = "<p class=\"docx-num-40-0\">Item</p>";

        String out = corrector.correct(html, mapper.writeValueAsString(profile));

        assertFalse(out.contains("margin-left"), out);
        assertFalse(out.contains("text-indent"), out);
    }

    /** An item whose numId has no entry in the profile is left alone —
     *  the corrector never invents geometry it wasn't given. */
    @Test
    void unknown_num_id_is_left_untouched() throws Exception {
        String profileJson = mapper.writeValueAsString(
                listProfile("40", 1080L, 360L, null, null));
        String html = "<p class=\"docx-num-99-0\">Item from another list</p>";

        String out = corrector.correct(html, profileJson);

        assertFalse(out.contains("margin-left"),
                "numId 99 is not in the profile — no indent may be applied: " + out);
    }

    /** Nested levels resolve independently: ilvl 1 must get the level-1
     *  geometry, not level 0's. */
    @Test
    void resolves_the_correct_level_for_nested_items() throws Exception {
        FormattingProfile.LevelDefinition l0 = new FormattingProfile.LevelDefinition(
                0, "bullet", "•",
                FormattingProfile.Length.fromTwips(1080),
                FormattingProfile.Length.fromTwips(-360));
        FormattingProfile.LevelDefinition l1 = new FormattingProfile.LevelDefinition(
                1, "bullet", "o",
                FormattingProfile.Length.fromTwips(1800),
                FormattingProfile.Length.fromTwips(-360));
        FormattingProfile profile = new FormattingProfile(
                2, null, null, null,
                new FormattingProfile.BodyDefault(null, null, null),
                Map.of("40", new FormattingProfile.ListDefinition(
                        "0", List.of(l0, l1), null, null)));
        String html = "<p class=\"docx-num-40-0\">Top level</p>"
                + "<p class=\"docx-num-40-1\">Nested</p>";

        String out = corrector.correct(html, mapper.writeValueAsString(profile));

        assertTrue(out.contains("margin-left:0.75in"), "level 0 = 1080tw: " + out);
        assertTrue(out.contains("margin-left:1.25in"), "level 1 = 1800tw: " + out);
    }

    // ── Consumer 3 (Stage 3) — list font sourced from the list ───────

    /**
     * ⭐ The mis-probe fix. {@code BodyDefault} is filled from the first
     * run in the document that names a font — on a letterhead document
     * that is the date / address line (Calibri here). Injecting it into
     * the bullets is what made them render in the wrong face. The list's
     * own runs (Times New Roman) are authoritative and must win.
     */
    @Test
    void prefers_the_lists_own_font_over_the_mis_probed_body_default() throws Exception {
        String profileJson = mapper.writeValueAsString(listProfile(
                "40", 1080L, 360L, "Times New Roman", "Calibri"));
        String html = "<p class=\"docx-num-40-0\">Duty one</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("font-family:Times New Roman"),
                "the list's own run font must win — this is the bullets-in-the-"
                        + "wrong-font fix: " + out);
        assertFalse(out.contains("font-family:Calibri"),
                "the letterhead font must NOT reach the bullets: " + out);
    }

    /**
     * A list whose runs name no font (they inherit) still needs
     * something sane — and a version-1 profile has no per-list font at
     * all. Both fall back to the body default, i.e. exactly the
     * pre-Stage-3 behaviour.
     */
    @Test
    void falls_back_to_body_default_when_the_list_names_no_font() throws Exception {
        String profileJson = mapper.writeValueAsString(listProfile(
                "40", 1080L, 360L, null, "Calibri"));
        String html = "<p class=\"docx-num-40-0\">Duty one</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("font-family:Calibri"),
                "body default remains the fallback when the list has no font "
                        + "of its own — keeps v1 profiles behaving as before: " + out);
    }

    /** Both corrections land on the same element without clobbering
     *  each other — the real-world shape for every duty bullet. */
    @Test
    void indent_and_font_are_applied_together_on_one_item() throws Exception {
        String profileJson = mapper.writeValueAsString(listProfile(
                "40", 1080L, 360L, "Times New Roman", "Calibri"));
        String html = "<p class=\"docx-num-40-0\">Duty one</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("margin-left:0.75in"), out);
        assertTrue(out.contains("text-indent:-0.25in"), out);
        assertTrue(out.contains("font-family:Times New Roman"), out);
    }

    /** {@code <li>} carrying the same class is corrected too, so a
     *  converter change doesn't silently drop the fix. */
    @Test
    void corrects_li_elements_carrying_the_docx_num_class() throws Exception {
        String profileJson = mapper.writeValueAsString(
                listProfile("40", 1080L, 360L, "Times New Roman", null));
        String html = "<ul><li class=\"docx-num-40-0\">Item</li></ul>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("margin-left:0.75in"), out);
        assertTrue(out.contains("font-family:Times New Roman"), out);
    }

    /**
     * Stage-3 backward compatibility. A version-1 profile — no per-list
     * font, no numbering map — must leave list items exactly as Stage 2
     * left them: body-default font injected, no indent invented.
     */
    @Test
    void version_1_profile_behaves_exactly_as_before_stage_3() throws Exception {
        FormattingProfile v1 = new FormattingProfile(
                1, null, null, null,
                new FormattingProfile.BodyDefault("Calibri", null, null),
                Map.of());
        String html = "<p class=\"docx-num-1-0\">Legacy bullet</p>";

        String out = corrector.correct(html, mapper.writeValueAsString(v1));

        assertTrue(out.contains("font-family:Calibri"),
                "Stage 2 behaviour preserved for v1 profiles: " + out);
        assertFalse(out.contains("margin-left"),
                "no numbering map means no indent may be invented: " + out);
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

    /**
     * Build a profile carrying ONE numbering definition, shaped like the
     * ANVI duty list. {@code hangingTwips} is given as Word writes it
     * (positive) and stored the way Stage 1 normalises it (negative
     * first-line), so the test reads the same way the DOCX does.
     */
    private FormattingProfile listProfile(String numId, long leftTwips,
            Long hangingTwips, String listFont, String bodyFont) {
        FormattingProfile.Length left = leftTwips == 0L
                ? null : FormattingProfile.Length.fromTwips(leftTwips);
        FormattingProfile.Length firstLine = hangingTwips == null
                ? null : FormattingProfile.Length.fromTwips(-hangingTwips);
        FormattingProfile.LevelDefinition level =
                new FormattingProfile.LevelDefinition(
                        0, "bullet", "•", left, firstLine);
        FormattingProfile.ListDefinition def = new FormattingProfile.ListDefinition(
                "0", List.of(level), listFont, null);
        return new FormattingProfile(
                2, null, null, null,
                new FormattingProfile.BodyDefault(bodyFont, null, null),
                Map.of(numId, def));
    }

    // ── data-fmt-admin: deliberate admin overrides survive ────────────
    //
    // The corrector re-asserts the imported profile on EVERY save, which
    // is right for repairing what docx-preview inferred at import — but
    // it cannot otherwise distinguish an inferred value from an admin's
    // considered override, so it silently reverted every page-margin and
    // list-indent edit the studio's formatting toolbar made. The
    // data-fmt-admin marker is that missing signal. These tests pin both
    // halves of the contract: marked elements keep the admin's values,
    // UNMARKED ones still get the profile re-asserted exactly as before.

    /** Letter page + 1in margins — the profile an admin is overriding. */
    private String letterOneInchProfileJson() throws Exception {
        FormattingProfile.PageGeometry page = new FormattingProfile.PageGeometry(
                FormattingProfile.Length.fromTwips(12240),  // 8.5in
                FormattingProfile.Length.fromTwips(15840),  // 11in
                "portrait",
                new FormattingProfile.Margins(
                        FormattingProfile.Length.fromTwips(1440),
                        FormattingProfile.Length.fromTwips(1440),
                        FormattingProfile.Length.fromTwips(1440),
                        FormattingProfile.Length.fromTwips(1440),
                        FormattingProfile.Length.fromTwips(720),
                        FormattingProfile.Length.fromTwips(720),
                        null));
        return mapper.writeValueAsString(new FormattingProfile(
                1, page, null, null,
                new FormattingProfile.BodyDefault(null, null, null),
                Map.of()));
    }

    @Test
    void marked_section_keeps_the_admins_page_margins() throws Exception {
        String html = "<section class=\"docx\" data-fmt-admin=\"1\" "
                + "style=\"width:8.5in;min-height:11in;padding-top:1.5in;"
                + "padding-right:0.5in;padding-bottom:0.5in;padding-left:0.5in;\">"
                + "<p>Body</p></section>";

        String out = corrector.correct(html, letterOneInchProfileJson());

        assertTrue(out.contains("padding-top:1.5in"),
                "admin's 1.5in top margin must survive the corrector: " + out);
        assertTrue(out.contains("padding-left:0.5in"), out);
        assertTrue(out.contains("padding-right:0.5in"), out);
        assertTrue(out.contains("padding-bottom:0.5in"), out);
        assertFalse(out.contains("padding-top:1.00in"),
                "profile margin must NOT overwrite a marked section: " + out);
        // Property-scoped skip: width / min-height aren't toolbar-owned,
        // so the profile's authoritative page SIZE is still applied.
        assertTrue(out.contains("width:8.50in"),
                "non-toolbar properties must still be corrected: " + out);
        assertTrue(out.contains("min-height:11.00in"), out);
        assertTrue(out.contains("data-fmt-admin"),
                "marker must persist for the NEXT save: " + out);
    }

    @Test
    void unmarked_section_still_gets_profile_margins_reasserted() throws Exception {
        String html = "<section class=\"docx\" "
                + "style=\"padding-top:1.5in;padding-left:0.5in;\">"
                + "<p>Body</p></section>";

        String out = corrector.correct(html, letterOneInchProfileJson());

        assertTrue(out.contains("padding-top:1.00in"),
                "unmarked section must still be corrected to the profile: " + out);
        assertTrue(out.contains("padding-left:1.00in"), out);
        assertFalse(out.contains("padding-top:1.5in"),
                "stale inferred value must be replaced on an unmarked section: " + out);
    }

    @Test
    void marked_list_paragraph_keeps_the_admins_indent() throws Exception {
        // Profile level says 0.25in left / -0.25in hanging.
        String profileJson = mapper.writeValueAsString(
                listProfile("1", 360L, 360L, null, null));
        String html = "<p class=\"docx-num-1-0\" data-fmt-admin=\"1\" "
                + "style=\"margin-left:2in;text-indent:0in;\">Item</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("margin-left:2in"),
                "admin's 2in list indent must survive the corrector: " + out);
        assertFalse(out.contains("margin-left:0.25in"),
                "profile indent must NOT overwrite a marked list paragraph: " + out);
        assertTrue(out.contains("data-fmt-admin"),
                "marker must persist for the NEXT save: " + out);
    }

    @Test
    void unmarked_list_paragraph_still_gets_profile_indent_reasserted() throws Exception {
        String profileJson = mapper.writeValueAsString(
                listProfile("1", 360L, 360L, null, null));
        String html = "<p class=\"docx-num-1-0\" style=\"margin-left:2in;\">Item</p>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("margin-left:0.25in"),
                "unmarked list paragraph must still be corrected: " + out);
        assertFalse(out.contains("margin-left:2in"),
                "stale value must be replaced on an unmarked paragraph: " + out);
    }

    @Test
    void marked_header_keeps_the_admins_alignment_and_indent() throws Exception {
        String profileJson = mapper.writeValueAsString(
                minimalProfile(headerParagraph("center", 0L)));
        String html = "<header data-fmt-admin=\"1\" "
                + "style=\"text-align:right;margin-left:1in;\">"
                + "<p>Masthead</p></header>";

        String out = corrector.correct(html, profileJson);

        assertTrue(out.contains("text-align:right"),
                "admin's header alignment must survive: " + out);
        assertTrue(out.contains("margin-left:1in"), out);
        assertFalse(out.contains("text-align:center"),
                "profile alignment must NOT overwrite a marked header: " + out);
    }

    /**
     * Re-import safety: replacing the source DOCX clears the canonical
     * HTML, so the fresh docx-preview render carries NO markers and the
     * profile is applied in full. Proven here by running the corrector
     * over marker-free HTML — the admin then re-applies any overrides
     * against the new document, which is the correct behaviour.
     */
    @Test
    void fresh_reimported_html_has_no_markers_so_profile_applies_fully()
            throws Exception {
        String freshFromDocxPreview =
                "<section class=\"docx\" style=\"padding-top:2in;width:9in;\">"
                + "<p class=\"docx-num-1-0\" style=\"margin-left:3in;\">Item</p>"
                + "</section>";
        assertFalse(freshFromDocxPreview.contains("data-fmt-admin"),
                "a fresh docx-preview render carries no override markers");

        String out = corrector.correct(freshFromDocxPreview,
                mapper.writeValueAsString(new FormattingProfile(
                        1,
                        new FormattingProfile.PageGeometry(
                                FormattingProfile.Length.fromTwips(12240),
                                FormattingProfile.Length.fromTwips(15840),
                                "portrait",
                                new FormattingProfile.Margins(
                                        FormattingProfile.Length.fromTwips(1440),
                                        FormattingProfile.Length.fromTwips(1440),
                                        FormattingProfile.Length.fromTwips(1440),
                                        FormattingProfile.Length.fromTwips(1440),
                                        null, null, null)),
                        null, null,
                        new FormattingProfile.BodyDefault(null, null, null),
                        Map.of("1", new FormattingProfile.ListDefinition(
                                "0",
                                List.of(new FormattingProfile.LevelDefinition(
                                        0, "bullet", "•",
                                        FormattingProfile.Length.fromTwips(360),
                                        null)),
                                null, null)))));

        assertTrue(out.contains("padding-top:1.00in"),
                "re-imported page geometry must be fully corrected: " + out);
        assertTrue(out.contains("margin-left:0.25in"),
                "re-imported list indent must be fully corrected: " + out);
        assertFalse(out.contains("data-fmt-admin"),
                "no stale marker may appear from a re-import: " + out);
    }
}
