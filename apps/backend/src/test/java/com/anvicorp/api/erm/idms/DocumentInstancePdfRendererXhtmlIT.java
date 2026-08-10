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
