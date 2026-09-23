package com.anvicorp.api.erm.idms;

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
 * Fix C — the shell may provide a FALLBACK, it may never OVERRIDE.
 *
 * <p>The renderer's shell stylesheet carried Anvi-tuned constants fitted
 * to Anvi's own offer letters, declared at a specificity that beat the
 * uploaded document. Two distinct failure shapes were measured:</p>
 * <ul>
 *   <li><b>Override</b> — {@code p[class*="docx-num"]} at (0,1,1) beat a
 *       document's class rule at (0,1,0); {@code header{font-size:9pt}}
 *       was a direct declaration, so it beat the document's font
 *       inherited from body.</li>
 *   <li><b>Stacking</b> — {@code padding-left:0.5em} on a list paragraph
 *       never conflicted with the document's {@code margin-left}: the two
 *       summed. Every source indent rendered 6pt deeper than asked, and a
 *       list with no source indent got 30pt invented. No cascade rule can
 *       resolve that; the declaration had to go.</li>
 * </ul>
 *
 * <p>Each constant now lives on a single marker class emitted BEFORE the
 * document's hoisted stylesheet, so a document rule wins on order (equal
 * specificity), on specificity, or by being inline. These tests measure
 * the executed PDF for both halves of the contract: the document's value
 * renders exactly, and a silent document still gets a sane fallback.</p>
 */
class ShellFallbackNeverOverridesIT {

    private final DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();

    private static final String SECTION =
        "<section class=\"docx\" style=\"width:8.5in;min-height:11in;"
        + "padding-top:1in;padding-right:1in;padding-bottom:1in;padding-left:1in;\">";

    private static String wrap(String inner) {
        return SECTION + "<article class=\"docx\">" + inner + "</article></section>";
    }

    private byte[] render(String body) {
        return renderer.renderToPdf(body, Map.of(), Map.of(), "fallback");
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

    private static int indexOfWord(List<Glyph> gs, String word) {
        for (int i = 0; i + word.length() <= gs.size(); i++) {
            boolean hit = true;
            for (int j = 0; j < word.length(); j++) {
                if (!gs.get(i + j).ch().equals(String.valueOf(word.charAt(j)))) {
                    hit = false;
                    break;
                }
            }
            if (hit) return i;
        }
        return -1;
    }

    private float xOf(byte[] pdf, String word) throws Exception {
        List<Glyph> gs = glyphs(pdf);
        int i = indexOfWord(gs, word);
        if (i < 0) throw new AssertionError("not rendered: " + word);
        return gs.get(i).x();
    }

    private float yOf(byte[] pdf, String word) throws Exception {
        List<Glyph> gs = glyphs(pdf);
        int i = indexOfWord(gs, word);
        if (i < 0) throw new AssertionError("not rendered: " + word);
        return gs.get(i).y();
    }

    /** Width of a run of 'M' glyphs — the honest ruler for type size. */
    private float mRun(byte[] pdf) throws Exception {
        List<Glyph> gs = glyphs(pdf);
        int st = -1, en = -1;
        for (int i = 0; i < gs.size(); i++) {
            if (gs.get(i).ch().equals("M")) {
                if (st < 0) st = i;
                en = i;
            } else if (st >= 0 && en - st >= 3) {
                break;
            } else if (st >= 0) {
                st = -1;
                en = -1;
            }
        }
        return (st < 0 || en <= st) ? -1f : gs.get(en).x() - gs.get(st).x();
    }

    private float plainParagraphX() throws Exception {
        return xOf(render(wrap("<p><span>AAA</span></p>")), "AAA");
    }

    // ── (a) THE MANDATORY ONE — list indent renders EXACTLY ──────────

    @Test
    void documentListIndentRendersExactlyWithNoStackedPadding() throws Exception {
        float plain = plainParagraphX();
        float indented = xOf(render(wrap(
                "<p class=\"docx-num-1-0\" style=\"margin-left:0.5in\">"
                + "<span>BBB</span></p>")), "BBB");

        // 0.5in = 36pt. The old shell added padding-left:0.5em on top,
        // so this used to land at +42pt.
        assertEquals(36f, indented - plain, 0.5f,
                "the document's list indent must render exactly — no stacked "
                        + "padding (was +42pt for a 36pt request)");
    }

    @Test
    void documentListIndentFromAClassRuleAlsoWins() throws Exception {
        float plain = plainParagraphX();
        float indented = xOf(render(
                "<style>.docx-num-1-0{margin-left:1in;}</style>"
                + wrap("<p class=\"docx-num-1-0\"><span>DDD</span></p>")), "DDD");

        assertEquals(72f, indented - plain, 1.0f,
                "a document class rule must beat the shell's fallback indent");
    }

    // ── (b) silent document still gets a sane fallback ───────────────

    @Test
    void silentListGetsTheNeutralFallbackIndent() throws Exception {
        float plain = plainParagraphX();
        float fallback = xOf(render(wrap(
                "<p class=\"docx-num-1-0\"><span>CCC</span></p>")), "CCC");

        // 2em at 12pt = 24pt, applied once. The old rule stacked
        // margin-left:2em + padding-left:0.5em = 30pt of invention.
        assertEquals(24f, fallback - plain, 1.0f,
                "a list with no document indent gets one clean fallback indent");
        assertTrue(fallback > plain,
                "a bullet list must still be indented relative to body text");
    }

    // ── (c) header/footer typography ─────────────────────────────────

    @Test
    void documentHeaderTypographyBeatsTheShellFallback() throws Exception {
        String bare = SECTION + "<header><p><span>MMMMMMMMMM</span></p></header>"
                + "<article class=\"docx\"><p>x</p></article></section>";
        String styled = "<style>.hdr{font-size:16pt;}</style>"
                + SECTION + "<header><p><span class=\"hdr\">MMMMMMMMMM</span></p></header>"
                + "<article class=\"docx\"><p>x</p></article></section>";

        float fallbackWidth = mRun(render(bare));
        float documentWidth = mRun(render(styled));

        assertTrue(documentWidth > fallbackWidth * 1.4f,
                "the document's header font must win over the shell's 9pt "
                        + "fallback (" + fallbackWidth + " -> " + documentWidth + ")");
    }

    @Test
    void silentHeaderStillGetsTheFallbackTypography() {
        String shell = renderer.toXhtmlForTest("t",
                SECTION + "<header><p><span>H</span></p></header>"
                + "<article class=\"docx\"><p>x</p></article></section>");
        assertTrue(shell.contains("." + DocumentInstancePdfRenderer.FALLBACK_HF),
                "the header fallback rule must still be emitted: " + shell);
        assertTrue(shell.contains(DocumentInstancePdfRenderer.FALLBACK_HF + "\""),
                "and stamped onto the header element: " + shell);
    }

    // ── (d) heading spacing + table cell padding ─────────────────────

    @Test
    void documentHeadingSpacingBeatsTheShellFallback() throws Exception {
        float fallback = yOf(render(wrap(
                "<p><span>TOP</span></p><h2 class=\"docx_H\"><span>HHH</span></h2>")), "HHH");
        float document = yOf(render(
                "<style>.docx_H{margin-top:60pt;}</style>"
                + wrap("<p><span>TOP</span></p><h2 class=\"docx_H\"><span>HHH</span></h2>")),
                "HHH");

        assertTrue(document - fallback > 40f,
                "the document's heading margin must win over the shell's 12pt "
                        + "fallback (" + fallback + " -> " + document + ")");
    }

    /**
     * The heading fallback used to be the shorthand {@code margin: 12pt 0
     * 6pt}, which also set margin-left/right to 0 — clobbering a document
     * that had only ever spoken about margin-left. Longhand now.
     */
    @Test
    void headingFallbackDoesNotClobberAPropertyTheDocumentDidNotContest()
            throws Exception {
        float bare = xOf(render(wrap("<h2><span>HHH</span></h2>")), "HHH");
        float indented = xOf(render(
                "<style>.docx_H{margin-left:1in;}</style>"
                + wrap("<h2 class=\"docx_H\"><span>HHH</span></h2>")), "HHH");

        assertEquals(72f, indented - bare, 2.0f,
                "a vertical-spacing fallback must not reach across to "
                        + "margin-left");
    }

    @Test
    void documentCellPaddingBeatsTheShellFallback() throws Exception {
        float fallback = xOf(render(wrap(
                "<table><tr><td><span>EEE</span></td></tr></table>")), "EEE");
        float inlineDoc = xOf(render(wrap(
                "<table><tr><td style=\"padding-left:0.5in\">"
                + "<span>EEE</span></td></tr></table>")), "EEE");
        float classDoc = xOf(render(
                "<style>.docx_C{padding-left:0.5in;}</style>"
                + wrap("<table><tr><td class=\"docx_C\"><span>EEE</span></td>"
                + "</tr></table>")), "EEE");

        assertTrue(inlineDoc - fallback > 25f,
                "inline cell padding must win: " + fallback + " -> " + inlineDoc);
        assertTrue(classDoc - fallback > 25f,
                "a class-rule cell padding must win: " + fallback + " -> " + classDoc);
    }

    // ── The shell must be emitted BEFORE the document's stylesheet ───

    @Test
    void fallbackRulesAreEmittedBeforeTheDocumentStylesheet() {
        String shell = renderer.toXhtmlForTest("t",
                "<style>.docx_H{margin-top:60pt;}</style>"
                + wrap("<h2 class=\"docx_H\"><span>H</span></h2>"));

        int fallbackRule = shell.indexOf("." + DocumentInstancePdfRenderer.FALLBACK_HEADING);
        int documentRule = shell.indexOf(".docx_H{margin-top:60pt;}");
        assertTrue(fallbackRule >= 0, "fallback rule must be emitted");
        assertTrue(documentRule > fallbackRule,
                "the document's stylesheet must come AFTER the fallbacks so it "
                        + "wins a same-specificity tie");
    }

    /** The stacking declaration must be gone outright — no cascade can
     *  resolve a padding-left that simply adds to a margin-left. */
    @Test
    void theStackingPaddingDeclarationIsGone() {
        String shell = renderer.toXhtmlForTest("t",
                wrap("<p class=\"docx-num-1-0\"><span>x</span></p>"));
        assertFalse(shell.contains("padding-left: 0.5em"),
                "the stacking padding-left must not be reintroduced: " + shell);
        // list-style-position stays — it is structure, not a document value.
        assertTrue(shell.contains("list-style-position: outside !important"),
                "bullet positioning is structural and must survive: " + shell);
    }

    // ── Deliberately untouched constants ─────────────────────────────

    @Test
    void appLevelAndStructuralConstantsAreLeftAlone() {
        String shell = renderer.toXhtmlForTest("t", wrap("<p><span>x</span></p>"));
        // Signature sizing is an app-level choice — the document never
        // states a signature size, so there is nothing to override.
        assertTrue(shell.contains("max-height: " + "2.6em")
                        || shell.contains("max-height:"),
                "signature sizing must remain: " + shell);
        // Page geometry defaults are already fallback-only (they fire
        // only when the document carries no section geometry).
        assertTrue(shell.contains("@page"), "page rule must remain");
        // Structural guards.
        assertTrue(shell.contains("box-sizing: border-box"), "structural guard");
        assertTrue(shell.contains("word-wrap: break-word"), "overflow guard");
        assertTrue(shell.contains("running(docHeader)")
                        || !shell.contains("<header"),
                "running-header machinery untouched");
    }

    // ── declaresAny() — the one real check in the stamping pass ──────

    @Test
    void declaresAnyMatchesWholePropertyNamesOnly() {
        assertTrue(DocumentInstancePdfRenderer.declaresAny(
                "margin-left: 1in", "margin-left"));
        assertTrue(DocumentInstancePdfRenderer.declaresAny(
                "color:red;text-indent:-2pt", "margin-left", "text-indent"));
        assertFalse(DocumentInstancePdfRenderer.declaresAny(
                "margin-right: 1in", "margin-left"));
        assertFalse(DocumentInstancePdfRenderer.declaresAny("", "margin-left"));
        assertFalse(DocumentInstancePdfRenderer.declaresAny(null, "margin-left"));
        // must not match a property whose name merely contains the target
        assertFalse(DocumentInstancePdfRenderer.declaresAny(
                "-webkit-margin-left: 1in", "margin-left"));
    }
}
