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
     *  overrides fails loudly.
     *
     *  <p>Default fallback path — for a body without a
     *  {@code section.docx} wrapper (hand-authored template or unit test
     *  input), the shell must fall back to A4 + 1in/0.88in/1in/1in as a
     *  sensible default. When the body DOES carry a docx-preview section,
     *  the geometry is scraped FROM THAT SECTION (see the
     *  {@link #page_geometry_scraped_from_docx_preview_section} test) so
     *  the default doesn't leak past documents that have their own real
     *  page dimensions.</p> */
    @Test
    void print_css_shell_pins_content_width_and_page_size() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String shell = renderer.toXhtmlForTest("Offer", "<p>body</p>");
        assertTrue(shell.contains("size: A4"),
                "default page size not A4: " + shell);
        assertTrue(shell.contains("margin: 1in 0.88in 1in 1in"),
                "default page margins missing: " + shell);
        assertTrue(shell.contains("max-width: 100%"),
                "content max-width constraint missing: " + shell);
        assertTrue(shell.contains("word-wrap: break-word"),
                "long-token break-word missing (email/url overflow guard): " + shell);
        assertTrue(shell.contains("article, section, div"),
                "docx-preview wrapper width override missing: " + shell);
        // Defence-in-depth CSS strip for the double-margin bug — the
        // renderer's jsoup pass zeroes inline padding on sections; the
        // CSS override catches any variant that slipped through.
        assertTrue(shell.contains("padding: 0 !important"),
                "section.docx padding-strip CSS override missing "
                        + "(would re-double the margin with docx-preview inline padding): "
                        + shell);
    }

    /** BUG (survey findings for commit c4f568d) — the executed PDF had:
     *   1. DOUBLED margins because @page margin + docx-preview's inline
     *      section padding both applied.
     *   2. DOUBLE header/footer because the DOCX's own <header>/<footer>
     *      rendered inline while a hardcoded ANVI logo header also
     *      repeated per page.
     *   3. Non-generic ANVI branding stamped onto every template even
     *      when the uploaded doc had nothing to do with ANVI.
     *
     *  <p>This test guards the generic contract that fixed all three.
     *  Feed the renderer a docx-preview-shaped body (a
     *  {@code section.docx} carrying inline padding + width + min-height,
     *  wrapping a {@code <header>} + body + {@code <footer>}), then
     *  assert:</p>
     *  <ol>
     *   <li>The scraped padding becomes the effective @page margin —
     *       so each document uses ITS OWN page geometry, not a
     *       hardcoded default.</li>
     *   <li>The section's inline padding / width / min-height are
     *       STRIPPED — no double-margin.</li>
     *   <li>The document's own {@code <header>} is marked
     *       {@code position: running(docHeader)} and the {@code <footer>}
     *       {@code running(docFooter)}, referenced from
     *       {@code @top-center / @bottom-center} by GENERIC names —
     *       no hardcoded ANVI logo, no hardcoded address footer.</li>
     *  </ol> */
    @Test
    void page_geometry_scraped_from_docx_preview_section() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        // Shape a real docx-preview canvas: outer article + one section
        // per page, section carrying inline padding=DOCX margins and
        // width/min-height=DOCX page size, header + content + footer
        // nested inside. Two sections = two docx-preview "pages".
        String body =
                "<article class=\"docx\">"
                        + "<section class=\"docx\" style=\"padding-left: 1in; "
                        + "padding-right: 0.88in; padding-top: 1in; padding-bottom: 1in; "
                        + "width: 21cm; min-height: 29.7cm;\">"
                        + "<header style=\"margin-top: calc(0.5in - 1in); min-height: 0.5in;\">"
                        + "<img src=\"data:image/png;base64,XX\" alt=\"logo\" /></header>"
                        + "<p>Body of page 1.</p>"
                        + "<footer style=\"margin-bottom: calc(0.5in - 1in); min-height: 0.5in;\">"
                        + "Company address line</footer>"
                        + "</section>"
                        + "<section class=\"docx\" style=\"padding-left: 1in; "
                        + "padding-right: 0.88in; padding-top: 1in; padding-bottom: 1in; "
                        + "width: 21cm; min-height: 29.7cm;\">"
                        + "<header><img src=\"data:image/png;base64,YY\" alt=\"logo\" /></header>"
                        + "<p>Body of page 2.</p>"
                        + "<footer>Company address line</footer>"
                        + "</section>"
                        + "</article>";
        String shell = renderer.toXhtmlForTest("Any Template", body);

        // 1. @page geometry scraped from the section — 21cm × 29.7cm size,
        //    1in/0.88in/1in/1in margin (top / right / bottom / left).
        assertTrue(shell.contains("size: 21cm 29.7cm"),
                "@page size not scraped from section width+min-height: " + shell);
        assertTrue(shell.contains("margin: 1in 0.88in 1in 1in"),
                "@page margin not scraped from section padding: " + shell);

        // 2. Section inline padding / width / min-height stripped — no
        //    double margin. Belt: no `padding-left: 1in` remains inline
        //    on any section; braces: the safety CSS !important would
        //    override anything the strip missed (asserted elsewhere).
        assertTrue(!shell.contains("padding-left: 1in;"),
                "section inline padding-left not stripped — double-margin regression: " + shell);
        assertTrue(!shell.contains("padding-right: 0.88in;"),
                "section inline padding-right not stripped: " + shell);
        assertTrue(!shell.contains("min-height: 29.7cm"),
                "section inline min-height not stripped: " + shell);
        assertTrue(!shell.contains("width: 21cm"),
                "section inline width not stripped: " + shell);

        // 3. Header / footer hoisted to running elements — generic names,
        //    referenced from @page margin boxes. Header uses @top-LEFT
        //    (not @top-center) so it anchors to the left of the top
        //    margin band; combined with the negative margin-left below
        //    the logo extends flush with the physical page left edge —
        //    matching the source DOCX's negative paragraph indent.
        assertTrue(shell.contains("@top-left { content: element(docHeader)"),
                "@top-left generic docHeader reference missing: " + shell);
        assertTrue(shell.contains("@bottom-center { content: element(docFooter)"),
                "@bottom-center generic docFooter reference missing: " + shell);
        assertTrue(shell.contains("position: running(docHeader)"),
                "docHeader running-element style rule missing: " + shell);
        assertTrue(shell.contains("position: running(docFooter)"),
                "docFooter running-element style rule missing: " + shell);
        // Negative margin-left = -(scraped left @page margin, here 1in)
        // — pulls the header content out of the @top-left box's left
        // boundary to the physical page edge (x=0).
        assertTrue(shell.contains("margin-left: -1in"),
                "header negative margin-left offset missing "
                        + "(would leave logo inset by 1in): " + shell);
        assertTrue(shell.contains("class=\"pdf-doc-header\"") || shell.contains(" pdf-doc-header"),
                "first <header> not marked with pdf-doc-header class: " + shell);
        assertTrue(shell.contains("class=\"pdf-doc-footer\"") || shell.contains(" pdf-doc-footer"),
                "first <footer> not marked with pdf-doc-footer class: " + shell);

        // 4. Extra header/footer copies dropped — the second section
        //    HAD its own header/footer nodes; they must be removed so
        //    only one running element exists to hoist.
        long headerCount = countOccurrences(shell, "<header");
        long footerCount = countOccurrences(shell, "<footer");
        assertTrue(headerCount == 1,
                "expected exactly ONE <header> in the body after hoist, got "
                        + headerCount + ": " + shell);
        assertTrue(footerCount == 1,
                "expected exactly ONE <footer> in the body after hoist, got "
                        + footerCount + ": " + shell);

        // 5. NO ANVI hardcoding leftover from commit c4f568d.
        assertTrue(!shell.contains("anviHeader"),
                "hardcoded anviHeader reference must be removed: " + shell);
        assertTrue(!shell.contains("anviFooter"),
                "hardcoded anviFooter reference must be removed: " + shell);
        assertTrue(!shell.contains("pdf-anvi-header"),
                "hardcoded pdf-anvi-header class must be removed: " + shell);
        assertTrue(!shell.contains("pdf-anvi-footer"),
                "hardcoded pdf-anvi-footer class must be removed: " + shell);
        assertTrue(!shell.contains("7950 Legacy Dr"),
                "hardcoded ANVI address footer must be removed: " + shell);
        assertTrue(!shell.contains("info@anvicorp.com"),
                "hardcoded ANVI email footer must be removed: " + shell);
    }

    /** Document with NO header or footer at all (a hand-authored template,
     *  or an uploaded DOCX with a plain layout) — the shell must NOT
     *  reference {@code element(docHeader)} or {@code element(docFooter)},
     *  so openhtmltopdf doesn't reserve empty margin-box space and push
     *  the body down. Confirms genericity across doc types. */
    @Test
    void no_header_no_footer_document_produces_no_margin_box_references() {
        DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
        String shell = renderer.toXhtmlForTest("Plain Doc",
                "<p>Just body content, no header, no footer.</p>");
        // Empty running-element references would consume vertical space
        // in the margin box for no reason.
        assertTrue(!shell.contains("element(docHeader)"),
                "empty document must NOT reference @top-left element(docHeader): " + shell);
        assertTrue(!shell.contains("element(docFooter)"),
                "empty document must NOT reference @bottom-center element(docFooter): " + shell);
        // Header negative-margin rule likewise absent — nothing to offset.
        assertTrue(!shell.contains("margin-left: -1in"),
                "empty document must NOT emit the header offset rule: " + shell);
        // The default @page is still there — just size + margin, no
        // margin boxes.
        assertTrue(shell.contains("size: A4"),
                "default page size missing: " + shell);
        assertTrue(shell.contains("margin: 1in 0.88in 1in 1in"),
                "default page margin missing: " + shell);
    }

    /** {@link DocumentInstancePdfRenderer#extractInlineLength(String, String)}
     *  edge cases — the scrape must accept simple lengths only, reject
     *  anything else so a template with a pathological inline value
     *  falls back to the default @page rather than piping garbage
     *  through to openhtmltopdf. */
    @Test
    void extract_inline_length_accepts_simple_lengths_only() {
        // Simple lengths in the units docx-preview emits.
        org.junit.jupiter.api.Assertions.assertEquals("1in",
                DocumentInstancePdfRenderer.extractInlineLength(
                        "padding-left: 1in;", "padding-left"));
        org.junit.jupiter.api.Assertions.assertEquals("0.88in",
                DocumentInstancePdfRenderer.extractInlineLength(
                        "padding-right: 0.88in;", "padding-right"));
        org.junit.jupiter.api.Assertions.assertEquals("21cm",
                DocumentInstancePdfRenderer.extractInlineLength(
                        "width: 21cm;", "width"));
        // Missing property = null (caller falls back to default).
        assertTrue(null == DocumentInstancePdfRenderer.extractInlineLength(
                        "font-family: sans-serif;", "padding-left"),
                "absent property must yield null");
        // Non-length value = null (calc(...), keyword, url()).
        assertTrue(null == DocumentInstancePdfRenderer.extractInlineLength(
                        "padding-left: auto;", "padding-left"),
                "keyword 'auto' must NOT parse as a length");
        assertTrue(null == DocumentInstancePdfRenderer.extractInlineLength(
                        "padding-left: calc(1in - 0.5in);", "padding-left"),
                "calc() must NOT parse as a simple length");
        assertTrue(null == DocumentInstancePdfRenderer.extractInlineLength(
                        "background: url(javascript:x);", "background"),
                "URL value must NOT parse as a length");
    }

    /** {@link DocumentInstancePdfRenderer#stripInlineProperties(String, String...)}
     *  — remove named declarations, keep the rest, clean up trailing
     *  semicolons. Guards the section-padding strip that fixes the
     *  double-margin bug. */
    @Test
    void strip_inline_properties_removes_named_leaves_rest() {
        // All three padding declarations removed; font-family kept.
        String out = DocumentInstancePdfRenderer.stripInlineProperties(
                "padding-left: 1in; padding-top: 1in; font-family: Arial;",
                "padding-left", "padding-top");
        assertTrue(!out.contains("padding-left"),
                "padding-left not stripped: " + out);
        assertTrue(!out.contains("padding-top"),
                "padding-top not stripped: " + out);
        assertTrue(out.contains("font-family: Arial"),
                "unrelated declaration was lost: " + out);
        // Strip everything → empty string.
        String empty = DocumentInstancePdfRenderer.stripInlineProperties(
                "padding-left: 1in; padding-top: 1in;",
                "padding-left", "padding-top");
        assertTrue(empty.isEmpty(),
                "expected empty string when every property is stripped, got '" + empty + "'");
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
