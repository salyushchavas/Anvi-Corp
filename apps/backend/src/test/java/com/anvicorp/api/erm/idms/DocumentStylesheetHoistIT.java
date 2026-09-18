package com.anvicorp.api.erm.idms;

import com.anvicorp.api.security.CanonicalHtmlSanitizer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fix A — the source document's OWN stylesheet must reach the PDF engine.
 *
 * <p>docx-preview splits a Word document's formatting in two: DIRECT
 * formatting becomes inline style, while STYLE-based formatting — the
 * {@code styles.xml} rules carrying the base font-family, font-size and
 * paragraph spacing of essentially every Word document — becomes CSS
 * CLASS RULES in a {@code <style>} element. {@code renderAsync()}
 * defaults its style container to the body container, so that element is
 * appended into the studio canvas, i.e. into the saved body HTML.</p>
 *
 * <p>Two things then went wrong, and together they were the largest
 * single source of "uploaded document != executed PDF":</p>
 * <ol>
 *   <li>{@link XhtmlNormalizer} parsed with jsoup's full-document parser,
 *       which hoists a leading {@code <style>} into {@code <head>}, and
 *       then returned only {@code body().html()} — so docx-preview's
 *       stylesheet was dropped at save and never stored at all.</li>
 *   <li>Where it did survive (nested deeper in the body), openhtmltopdf
 *       ignored it — that parser applies CSS only from {@code <head>} —
 *       AND rendered its CSS source as visible body text.</li>
 * </ol>
 *
 * <p>Either way the document's own typography collapsed to the shell's
 * {@code body { font-family: Times New Roman; font-size: 12pt }}, which
 * was tuned to Anvi's own offer letters. That is also why cloning the
 * platform rendered another brand's documents in Anvi's fonts.</p>
 */
class DocumentStylesheetHoistIT {

    private final DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();

    private static final String M = "MMMMMMMMMMMMMMMMMMMM";

    /** docx-preview's own output order: stylesheet FIRST, then content. */
    private static String docxPreviewShape(String css, String spanClass) {
        return "<style>" + css + "</style>"
            + "<section class=\"docx\" style=\"width:8.5in;min-height:11in;"
            + "padding-top:1in;padding-right:1in;padding-bottom:1in;padding-left:1in;\">"
            + "<article class=\"docx\"><p>"
            + "<span class=\"" + spanClass + "\">" + M + "</span>"
            + "</p></article></section>";
    }

    private byte[] render(String body) {
        return renderer.renderToPdf(body, Map.of(), Map.of(), "hoist");
    }

    private record Glyph(String ch, float x, float y) {}

    private static List<Glyph> glyphs(byte[] pdf) throws Exception {
        List<Glyph> out = new ArrayList<>();
        try (PDDocument d = PDDocument.load(new ByteArrayInputStream(pdf))) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String t, List<TextPosition> ps) {
                    for (TextPosition p : ps) {
                        out.add(new Glyph(p.getUnicode(), p.getXDirAdj(), p.getYDirAdj()));
                    }
                }
            };
            s.setSortByPosition(true);
            s.getText(d);
        }
        return out;
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument d = PDDocument.load(new ByteArrayInputStream(pdf))) {
            return new PDFTextStripper().getText(d).replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * Width of the contiguous run of 'M' glyphs. Rendered width is the
     * honest ruler for type size — PDFBox's per-glyph font-size reporting
     * quantises and will happily claim 19pt for a 20pt run.
     */
    private static float mRunWidth(byte[] pdf) throws Exception {
        List<Glyph> gs = glyphs(pdf);
        int start = -1, end = -1;
        for (int i = 0; i < gs.size(); i++) {
            if (gs.get(i).ch().equals("M")) {
                if (start < 0) start = i;
                end = i;
            } else if (start >= 0 && end - start >= 5) {
                break;
            } else if (start >= 0) {
                start = -1;
                end = -1;
            }
        }
        return (start < 0 || end <= start) ? -1f : gs.get(end).x() - gs.get(start).x();
    }

    /** The shell's own fallback — what a document used to collapse to. */
    private float shellDefaultWidth() throws Exception {
        return mRunWidth(render(
            "<section class=\"docx\" style=\"width:8.5in;min-height:11in;"
            + "padding-top:1in;padding-right:1in;padding-bottom:1in;padding-left:1in;\">"
            + "<article class=\"docx\"><p><span>" + M + "</span></p></article></section>"));
    }

    /** The same size expressed as DIRECT formatting, which always worked. */
    private float inlineWidth(String inlineStyle) throws Exception {
        return mRunWidth(render(
            "<section class=\"docx\" style=\"width:8.5in;min-height:11in;"
            + "padding-top:1in;padding-right:1in;padding-bottom:1in;padding-left:1in;\">"
            + "<article class=\"docx\"><p><span style=\"" + inlineStyle + "\">"
            + M + "</span></p></article></section>"));
    }

    // ── Proof (b): the class rule is APPLIED ─────────────────────────

    @Test
    void documentClassRuleFontSizeIsAppliedInTheExecutedPdf() throws Exception {
        float shellDefault = shellDefaultWidth();
        float wanted16pt = inlineWidth("font-size:16pt");
        float rendered = mRunWidth(render(
                docxPreviewShape(".docx_r_1{font-size:16pt;}", "docx_r_1")));

        // Sanity: the two reference points must actually differ, or the
        // assertion below would pass for the wrong reason.
        assertTrue(wanted16pt > shellDefault * 1.2f,
                "16pt must be visibly wider than the 12pt shell default");

        assertEquals(wanted16pt, rendered, 3.0f,
                "the document's own class rule must set the rendered size "
                        + "(shell default " + shellDefault + ", document asked "
                        + wanted16pt + ", got " + rendered + ")");
    }

    // ── Proof (c): font-family too ───────────────────────────────────

    @Test
    void documentClassRuleFontFamilyIsAppliedInTheExecutedPdf() throws Exception {
        // No fonts are bundled yet (that is Fix B), so Arial substitutes
        // to Helvetica and Times to Times-Roman — but those two have
        // different metrics, so a serif/sans switch is still observable
        // as a width change. That is enough to prove the rule APPLIED.
        float serif = shellDefaultWidth();
        float wantedSans = inlineWidth("font-family:Arial,sans-serif");
        float rendered = mRunWidth(render(docxPreviewShape(
                ".docx_r_1{font-family:Arial,sans-serif;}", "docx_r_1")));

        assertTrue(Math.abs(wantedSans - serif) > 3.0f,
                "serif vs sans must be distinguishable by width");
        assertEquals(wantedSans, rendered, 3.0f,
                "the document's font-family class rule must apply");
    }

    // ── Proof (d): the stylesheet no longer prints as body text ──────

    @Test
    void documentStylesheetIsNotRenderedAsVisibleText() throws Exception {
        byte[] pdf = render(docxPreviewShape(
                ".docx_r_1{font-family:Calibri;font-size:16pt;}", "docx_r_1"));
        String visible = text(pdf);

        assertFalse(visible.contains("font-family"),
                "CSS must not print as document text: \"" + visible + "\"");
        assertFalse(visible.contains("docx_r_1"),
                "CSS selector must not print as document text: \"" + visible + "\"");
        assertEquals(M, visible,
                "the PDF should contain the document's text and nothing else");
    }

    // ── The document beats the shell where they tie ──────────────────

    @Test
    void documentRuleOutranksTheShellDefaultForTheSameProperty() throws Exception {
        // The shell declares body{font-size:12pt}. A document rule on the
        // element itself must win — this is the ordering the fix depends
        // on (document <style> emitted AFTER the shell's).
        String shell = renderer.toXhtmlForTest("t",
                docxPreviewShape(".docx_r_1{font-size:16pt;}", "docx_r_1"));
        int shellBlock = shell.indexOf("font-family: 'Times New Roman'");
        int docBlock = shell.indexOf(".docx_r_1{font-size:16pt;}");
        assertTrue(shellBlock >= 0, "shell defaults must still be emitted");
        assertTrue(docBlock > shellBlock,
                "the document's stylesheet must be emitted AFTER the shell's "
                        + "so the document wins a tie");
        assertTrue(docBlock < shell.indexOf("<body>"),
                "the document's stylesheet must land in <head>, not the body");
    }

    // ── XhtmlNormalizer: a leading <style> must survive the save ─────

    @Test
    void normalizerKeepsALeadingStyleBlock() {
        String canvasHtml = docxPreviewShape(".docx_r_1{font-size:16pt;}", "docx_r_1");
        String normalized = XhtmlNormalizer.toXhtmlFragment(canvasHtml);

        assertTrue(normalized.contains("<style"),
                "a leading <style> must survive normalisation — jsoup hoists it "
                        + "to <head> and body().html() used to drop it: " + normalized);
        assertTrue(normalized.contains("font-size:16pt"),
                "the class rules themselves must survive: " + normalized);

        // And through the sanitizer, which already safelists <style>.
        String saved = CanonicalHtmlSanitizer.sanitize(normalized);
        assertTrue(saved.contains("<style"),
                "sanitizer must keep the stylesheet: " + saved);
    }

    /** Ordering of multiple stylesheets must be preserved — later rules
     *  win in CSS, so reversing them would change the winner. */
    @Test
    void normalizerPreservesTheOrderOfMultipleStyleBlocks() {
        String html = "<style>.a{font-size:10pt;}</style>"
                + "<style>.b{font-size:20pt;}</style>"
                + "<section class=\"docx\"><p>x</p></section>";
        String out = XhtmlNormalizer.toXhtmlFragment(html);
        assertTrue(out.indexOf(".a{") < out.indexOf(".b{"),
                "stylesheet order must be preserved: " + out);
    }

    // ── The hoist must not become an injection vector ────────────────

    @Test
    void hoistedCssIsScrubbedLikeInlineStyles() {
        String nasty = ".x{background:url(javascript:alert(1));}"
                + ".y{behavior:url(#default#time2);}"
                + ".z{width:expression(alert(1));}";
        String scrubbed = DocumentInstancePdfRenderer.scrubDocumentCss(nasty);
        assertFalse(scrubbed.toLowerCase().contains("javascript:"), scrubbed);
        assertFalse(scrubbed.toLowerCase().contains("behavior:"), scrubbed);
        assertFalse(scrubbed.toLowerCase().contains("expression("), scrubbed);
    }

    @Test
    void hoistedCssCannotCloseTheStyleElementEarly() {
        String breakout = ".x{color:red;}</style><script>alert(1)</script>";
        String scrubbed = DocumentInstancePdfRenderer.scrubDocumentCss(breakout);
        assertFalse(scrubbed.contains("</style"),
                "a closing style tag must be neutralised: " + scrubbed);
    }

    // ── No regression to the shipped fidelity fixes ──────────────────

    /**
     * The hoist removes {@code <style>} elements from the body. Prove that
     * pass didn't disturb the geometry scrape, the running header/footer,
     * or the inline declarations the formatting toolbar writes.
     */
    @Test
    void hoistDoesNotRegressGeometryHeaderFooterOrInlineStyles() throws Exception {
        String html = "<style>.docx_r_1{font-size:14pt;}</style>"
            + "<section class=\"docx\" style=\"width:8.5in;min-height:11in;"
            + "padding-top:1.5in;padding-right:0.5in;padding-bottom:0.5in;"
            + "padding-left:0.5in;\">"
            + "<header><p><span>HEADERMARK</span></p></header>"
            + "<article class=\"docx\">"
            + "<p style=\"line-height:2;margin-top:24pt;text-align:center\">"
            + "<span class=\"docx_r_1\">BODYMARK</span></p>"
            + "<p class=\"docx-num-1-0\" style=\"margin-left:2in\"><span>LISTMARK</span></p>"
            + "</article>"
            + "<footer><p><span>FOOTERMARK</span></p></footer>"
            + "</section>";

        String shell = renderer.toXhtmlForTest("t", html);
        // Page geometry still scraped from the section's padding.
        assertTrue(shell.contains("margin: 1.5in 0.5in 0.5in 0.5in"),
                "page geometry scrape must still work: " + shell);
        // Running header / footer still hoisted.
        assertTrue(shell.contains("running(docHeader)"), "header hoist must survive");
        assertTrue(shell.contains("running(docFooter)"), "footer hoist must survive");
        // Inline declarations untouched.
        assertTrue(shell.contains("line-height:2") || shell.contains("line-height: 2"),
                "inline line-height must survive: " + shell);
        assertTrue(shell.contains("margin-left:2in") || shell.contains("margin-left: 2in"),
                "inline list indent must survive: " + shell);
        // And the document's CSS made it to head.
        assertTrue(shell.indexOf(".docx_r_1{font-size:14pt;}") < shell.indexOf("<body>"),
                "document CSS must be in <head>");

        byte[] pdf = render(html);
        String visible = text(pdf);
        assertTrue(visible.contains("HEADERMARK"), "header must render: " + visible);
        assertTrue(visible.contains("FOOTERMARK"), "footer must render: " + visible);
        assertTrue(visible.contains("BODYMARK"), "body must render: " + visible);
        assertFalse(visible.contains("font-size"), "no CSS leak: " + visible);
    }

    /** A document with no stylesheet at all must render exactly as before. */
    @Test
    void documentWithoutAStylesheetIsUnaffected() throws Exception {
        String plain =
            "<section class=\"docx\" style=\"width:8.5in;min-height:11in;"
            + "padding-top:1in;padding-right:1in;padding-bottom:1in;padding-left:1in;\">"
            + "<article class=\"docx\"><p><span>" + M + "</span></p></article></section>";
        String shell = renderer.toXhtmlForTest("t", plain);
        // Exactly one <style> element — the shell's own.
        assertEquals(1, shell.split("<style", -1).length - 1,
                "no empty second <style> when the document has none: " + shell);
        assertEquals(M, text(render(plain)));
    }
}
