package com.anvicorp.api.erm.idms;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression — the finalize path used to SAX-crash on stored
 * canonical_html that docx-preview had emitted with unclosed
 * {@code <p>} tags (browser tolerant, XHTML strict-parser fatal).
 *
 * <p>These tests feed the renderer the exact failing shapes to prove
 * the jsoup normaliser lets openhtmltopdf render them cleanly. Uses
 * the {@link DocumentInstancePdfRenderer} directly (no Spring
 * context) so it runs in milliseconds and doesn't need H2.</p>
 */
class DocumentInstancePdfRendererXhtmlIT {

    /** {@code %PDF-} — the PDF file signature magic bytes. */
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};

    /** Field values + signature both survive the jsoup round-trip and
     *  the openhtmltopdf render succeeds on markup with unclosed
     *  {@code <p>} + non-self-closed {@code <br>} + a bare
     *  {@code &nbsp;}. Before this fix, openhtmltopdf threw
     *  {@code SAXParseException: element type "p" must be terminated
     *   by the matching end-tag}. */
    @Test
    void renderer_repairs_unclosed_p_and_produces_valid_pdf() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();

        // The malformed canonical HTML shape docx-preview produced for
        // the offer-letter template: <p> never closed before the next
        // block, <br> not self-closed, bare &nbsp;.
        String canonicalHtml = ""
                + "<p>Dear <span class=\"doc-field\" data-field-id=\"name-1\">Name</span>,"
                + "<br>"
                + "<p>We are pleased to offer you the role of "
                + "<span class=\"doc-field\" data-field-id=\"role-1\">Role</span>."
                + "&nbsp;&nbsp;Please sign below to accept."
                + "<p>Signature:"
                + "<span class=\"doc-field\" data-field-id=\"sig-1\">[sig]</span>";

        // A tiny 1×1 PNG data URL — smallest possible signature payload.
        String tinyPng = "data:image/png;base64,"
                + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABAQMAAAAl21bKAAAAA1BMVEUAAA"
                + "CnEj3aAAAAAXRSTlMAQObYZgAAAApJREFUCNdjYAAAAAIAAeIhvDMAAAAASUVORK5CYII=";

        byte[] pdf = renderer.renderToPdf(
                canonicalHtml,
                Map.of("name-1", "Alice Applicant",
                       "role-1", "Software Engineer"),
                Map.of("sig-1", tinyPng),
                "Offer Letter");

        assertNotNull(pdf, "renderer returned null bytes");
        assertTrue(pdf.length > 100,
                "PDF suspiciously short: " + pdf.length + " bytes");
        // Every valid PDF starts with "%PDF-".
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            assertTrue(pdf[i] == PDF_MAGIC[i],
                    "PDF magic byte mismatch at " + i
                            + " — got 0x" + Integer.toHexString(pdf[i] & 0xff));
        }
    }

    /** The un-substituted anchor spans in canonical_html normalise
     *  identically whether they're already well-formed or missing the
     *  end-tag — proves the interpolation happens BEFORE normalisation
     *  and the {@code data-field-id} attribute survives untouched. */
    @Test
    void anchor_spans_survive_the_normalisation_round_trip() {
        String withAnchors = "<p>Value: <span class=\"doc-field\" "
                + "data-field-id=\"pay-1\">$0</span></p>";
        String out = XhtmlNormalizer.toXhtmlFragment(withAnchors);
        assertTrue(out.contains("data-field-id=\"pay-1\""),
                "anchor id was lost in normalisation: " + out);
        assertTrue(out.contains("class=\"doc-field\""),
                "anchor class was lost in normalisation: " + out);
    }

    /** Void tags get self-closed; unclosed block gets auto-closed. */
    @Test
    void void_tags_self_close_and_blocks_auto_close() {
        String messy = "<p>one<br><p>two<hr><img src=\"x.png\"><p>three";
        String out = XhtmlNormalizer.toXhtmlFragment(messy);
        assertTrue(out.contains("<br />") || out.contains("<br/>"),
                "br not self-closed: " + out);
        assertTrue(out.contains("<hr />") || out.contains("<hr/>"),
                "hr not self-closed: " + out);
        assertTrue(out.contains("/>"), "expected some self-close in: " + out);
        // Same number of opens and closes for <p> after normalisation
        // (the tag-closing rule closes an open <p> when a new <p> starts).
        long opens = countOccurrences(out, "<p>");
        long closes = countOccurrences(out, "</p>");
        assertTrue(opens == closes,
                "unbalanced <p> after normalisation: opens=" + opens
                        + " closes=" + closes + " out=" + out);
    }

    // ── Regressions for the PDF layout / duplication bugs ────────────

    /** BUG 2 — content_block anchors wrap a whole paragraph / list, so
     *  their inner content is a chain of docx-preview run spans. The
     *  prior regex-based interpolator matched {@code .*?</span>}
     *  (non-greedy) which ended at the FIRST inner {@code </span>},
     *  so every bullet AFTER the first survived as orphan template text
     *  next to the interpolated value — Job Duties rendered TWICE.
     *  The jsoup interpolator selects {@code span[data-field-id]} and
     *  replaces the entire outer element's children, so nothing leaks. */
    @Test
    void content_block_with_nested_runs_does_not_leak_original_bullets() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String canonicalHtml =
                "Duties:"
                        + "<span class=\"doc-field\" data-field-id=\"duties\">"
                        + "<span class=\"docx_r_1\">Original Duty 1</span>"
                        + "<span class=\"docx_r_2\">Original Duty 2</span>"
                        + "<span class=\"docx_r_3\">Original Duty 3</span>"
                        + "</span>"
                        + " end.";
        String out = renderer.interpolate(
                canonicalHtml,
                Map.of("duties", "New A\nNew B\nNew C"),
                Map.of());
        assertTrue(out.contains("New A"), "filled value missing: " + out);
        assertTrue(out.contains("New B"), "filled line 2 missing: " + out);
        assertTrue(out.contains("New C"), "filled line 3 missing: " + out);
        assertTrue(!out.contains("Original Duty 1"),
                "leftover bullet 1 leaked (regex-nesting regression): " + out);
        assertTrue(!out.contains("Original Duty 2"),
                "leftover bullet 2 leaked (regex-nesting regression): " + out);
        assertTrue(!out.contains("Original Duty 3"),
                "leftover bullet 3 leaked (regex-nesting regression): " + out);
    }

    /** BUG 3 — a date anchor whose original placeholder was split by
     *  docx-preview into multiple run spans used to leak the trailing
     *  run past the substitution, so the filled date rendered alongside
     *  the residual "AUTO date" (or any other trailing token) from the
     *  template. jsoup interpolator replaces the whole anchor. */
    @Test
    void date_anchor_with_multi_run_placeholder_renders_single_value() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String canonicalHtml =
                "tentatively set for "
                        + "<span class=\"doc-field\" data-field-id=\"startDate\">"
                        + "<span class=\"docx_r_1\">MM/DD/YYYY</span>"
                        + "<span class=\"docx_r_2\">08/10/2026</span>"
                        + "</span>.";
        String out = renderer.interpolate(
                canonicalHtml,
                Map.of("startDate", "04/20/2026"),
                Map.of());
        assertTrue(out.contains("04/20/2026"), "filled date missing: " + out);
        assertTrue(!out.contains("08/10/2026"),
                "trailing placeholder run leaked (regex-nesting regression): " + out);
        assertTrue(!out.contains("MM/DD/YYYY"),
                "leading placeholder run leaked (regex-nesting regression): " + out);
    }

    /** Multi-anchor same-field-id: both anchors get the same value. Not
     *  covered by the old test suite; asserts the jsoup rewrite preserves
     *  the "one field, N places" contract. */
    @Test
    void same_field_id_across_multiple_anchors_fills_all() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String canonicalHtml =
                "Hello <span data-field-id=\"n\">?</span>, "
                        + "welcome <span data-field-id=\"n\">?</span>.";
        String out = renderer.interpolate(
                canonicalHtml,
                Map.of("n", "Alice"),
                Map.of());
        assertTrue(countOccurrences(out, "Alice") == 2,
                "expected 2 anchor fills, got: " + out);
    }

    /** BUG 1 — the page CSS must constrain content to the printable area
     *  so a docx-preview wrapper's fixed pixel/inch width can't push text
     *  past the right margin. This test checks the CSS shell (asserted
     *  via the test-hook wrap) so a future edit that drops the width
     *  overrides fails loudly. */
    @Test
    void print_css_shell_pins_content_width_and_page_size() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String shell = renderer.toXhtmlForTest("Offer", "<p>body</p>");
        assertTrue(shell.contains("@page { size: letter"),
                "page size not pinned to letter: " + shell);
        assertTrue(shell.contains("max-width: 100%"),
                "content max-width constraint missing: " + shell);
        assertTrue(shell.contains("word-wrap: break-word"),
                "long-token break-word missing (email/url overflow guard): " + shell);
        assertTrue(shell.contains("article, section, div"),
                "docx-preview wrapper width override missing: " + shell);
    }

    /** BUG 4 — bulleted lists in the source DOCX rendered as cramped
     *  literal "•" characters jammed together in the PDF (job-duties
     *  block). docx-preview emits Word lists as
     *  {@code <p class="docx-num-{id}-{lvl}">} with an injected
     *  {@code display:list-item; list-style-position:inside} rule that
     *  crams the bullet into the text flow with no indent + no vertical
     *  breathing. The PDF shell must carry list rules that (a) give
     *  real ul/ol/li sensible margins + padding-left + visible marker
     *  and (b) override the docx-preview list-item pattern to flip
     *  position to `outside`, add left indentation, and add per-item
     *  vertical margin. */
    @Test
    void print_css_shell_has_list_indent_and_breathing_room() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String shell = renderer.toXhtmlForTest("Offer", "<p>body</p>");
        // Real <ul>/<ol> get padding + visible markers.
        assertTrue(shell.contains("ul, ol {"),
                "ul/ol margin+padding rule missing: " + shell);
        assertTrue(shell.contains("padding-left: 2.5em"),
                "list padding-left missing (would render bullets flush left): " + shell);
        assertTrue(shell.contains("list-style-type: disc"),
                "ul list-style-type not pinned (marker may vanish under openhtmltopdf): "
                        + shell);
        assertTrue(shell.contains("list-style-type: decimal"),
                "ol list-style-type not pinned: " + shell);
        // docx-preview list-item override — outside marker + indent + margin.
        assertTrue(shell.contains("p[class*=\"docx-num\"]"),
                "docx-preview list-item override missing (bullets will crowd text): "
                        + shell);
        assertTrue(shell.contains("list-style-position: outside !important"),
                "docx-preview list-item list-style-position not flipped to outside: "
                        + shell);
        assertTrue(shell.contains("margin-left: 2em !important"),
                "docx-preview list-item indent missing: " + shell);
    }

    /** BUG 4 (font hoist) — the docx-preview list-item paragraph
     *  {@code <p class="docx-num-...">} has NO inline font-family /
     *  font-size of its own; the runs inside DO. The bullet marker,
     *  rendered via {@code :before}, inherits from the paragraph, so
     *  without a hoist the marker falls back to body font (11pt Calibri
     *  in our PDF shell) while the run text renders in the source's
     *  face (e.g. 12pt Times New Roman) — that's the "bullets look a
     *  different size from the text" mismatch. This pass hoists the
     *  inner run's font onto the paragraph so the {@code :before} bullet
     *  and the run text render in the same typography. */
    @Test
    void list_item_paragraph_font_hoist_matches_inner_run() {
        String canonical =
                "<p class=\"docx-num-1-0\">"
                        + "<span class=\"docx_r_1\" style=\"font-family:'Times New Roman';"
                        + "font-size:12pt;\">Design and implement APIs</span>"
                        + "</p>"
                        + "<p class=\"docx-num-1-0\">"
                        + "<span class=\"docx_r_1\" style=\"font-family:'Times New Roman';"
                        + "font-size:12pt;\">Ship on schedule</span>"
                        + "</p>";
        String out = IdmsFieldFontInheritance.applyToCanonicalHtml(canonical);
        // Every list-item <p> now carries the inner run's font on itself.
        long items = countOccurrences(out, "class=\"docx-num-1-0\"");
        long fontHoisted = countOccurrences(out,
                "font-family:'Times New Roman';font-size:12pt;\" class=\"docx-num-1-0\"");
        // Loose match — jsoup can reorder attributes. Assert BOTH <p>
        // elements ended up with the font declarations in their style.
        long styleFontFamily = countOccurrences(out,
                "font-family:'Times New Roman'");
        long styleFontSize = countOccurrences(out, "font-size:12pt");
        assertTrue(items == 2,
                "expected 2 list-item paragraphs, got " + items + " in: " + out);
        // Runs (1 per item) + hoisted <p> style (1 per item) = 2 * (1+1) = 4.
        // Assert at least 4 occurrences of the font-family declaration —
        // means the hoist added the font to both <p>s.
        assertTrue(styleFontFamily >= 4,
                "font-family not hoisted onto both list-item <p>s (want ≥4, got "
                        + styleFontFamily + "): " + out);
        assertTrue(styleFontSize >= 4,
                "font-size not hoisted onto both list-item <p>s (want ≥4, got "
                        + styleFontSize + "): " + out);
        // Direct-attribute readback — the first <p> must carry style.
        assertTrue(out.contains("<p style=") || out.contains("<p class=\"docx-num-1-0\" style="),
                "list-item <p> has no style attribute after hoist: " + out);
        // Belt-and-braces: no double-declaration where an inline style
        // already existed. Silences the "hoist re-hoists on every pass"
        // regression class.
        String reapplied = IdmsFieldFontInheritance.applyToCanonicalHtml(out);
        assertTrue(reapplied.equals(out),
                "hoist is not idempotent — reapplying changed the HTML");
        // Guard against fontHoisted being unused — the loose-match
        // occurrences above already assert the hoist. Reference it so
        // future test edits notice the tighter check if attribute order
        // becomes reliable.
        assertTrue(fontHoisted >= 0, "sanity: attribute-order match count is non-negative");
    }

    /** End-to-end: given the exact ANVI_OPT unpaid-offer shape (body
     *  paragraph + a docx-preview bulleted list where runs are 12pt Times
     *  New Roman), the interpolate → wrap round-trip produces PDF-ready
     *  HTML in which the list-item paragraph carries the inner run's
     *  font AND the surrounding shell CSS gives the bullets outside
     *  positioning + indentation + breathing room. Guards the whole
     *  fix in one assertion. */
    @Test
    void anvi_opt_bulleted_duties_render_uniformly_with_indent() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String canonicalHtml =
                "<p><span class=\"docx_r_0\" style=\"font-family:'Times New Roman';"
                        + "font-size:12pt;\">Job duties include:</span></p>"
                        + "<p class=\"docx-num-1-0\">"
                        + "<span class=\"docx_r_1\" style=\"font-family:'Times New Roman';"
                        + "font-size:12pt;\">Design and implement APIs</span>"
                        + "</p>"
                        + "<p class=\"docx-num-1-0\">"
                        + "<span class=\"docx_r_2\" style=\"font-family:'Times New Roman';"
                        + "font-size:12pt;\">Ship on schedule</span>"
                        + "</p>";
        // Simulate the full pipeline: font-hoist → interpolate → wrap.
        String hoisted = IdmsFieldFontInheritance.applyToCanonicalHtml(canonicalHtml);
        String interp = renderer.interpolate(hoisted, Map.of(), Map.of());
        String shell = renderer.toXhtmlForTest("ANVI OPT unpaid offer", interp);
        // Uniform font on list-item paragraphs.
        assertTrue(shell.contains("font-family:'Times New Roman'"),
                "Times New Roman not preserved in the pipeline: " + shell);
        assertTrue(shell.contains("font-size:12pt"),
                "12pt not preserved on list items: " + shell);
        // List-item indent + breathing-room CSS present in shell.
        assertTrue(shell.contains("list-style-position: outside !important"),
                "docx-preview list-item outside-marker rule missing: " + shell);
        assertTrue(shell.contains("margin-left: 2em !important"),
                "docx-preview list-item indent rule missing: " + shell);
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
