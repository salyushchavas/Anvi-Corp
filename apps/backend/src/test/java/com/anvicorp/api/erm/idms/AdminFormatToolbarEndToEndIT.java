package com.anvicorp.api.erm.idms;

import com.anvicorp.api.admin.editabletemplates.CanonicalHtmlProfileCorrector;
import com.anvicorp.api.security.CanonicalHtmlSanitizer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that every control in the studio's admin formatting
 * toolbar reaches the EXECUTED PDF — through the REAL save pipeline.
 *
 * <p>This exists because an earlier proof rendered canonical HTML
 * straight through {@link DocumentInstancePdfRenderer} and declared all
 * six controls working. It was measuring the wrong thing.
 * {@code EditableTemplateAdminService.saveSchema} runs
 * {@link XhtmlNormalizer} → {@link CanonicalHtmlProfileCorrector} →
 * {@link CanonicalHtmlSanitizer} BEFORE persisting, and the corrector
 * re-asserts the imported DOCX formatting profile on every save. Two
 * controls — page margins and list-paragraph indent — were silently
 * reverted there and never reached the renderer at all.</p>
 *
 * <p>The fix is the {@code data-fmt-admin} marker: the toolbar stamps
 * every element it restyles, and the corrector skips the properties the
 * toolbar owns on a marked element while continuing to repair
 * everything else. These tests pin that end to end — save pipeline
 * included, glyph positions measured out of the rendered PDF with
 * PDFBox, so a future change to the shell CSS, the corrector, or the
 * sanitizer that breaks an admin's formatting fails here.</p>
 */
class AdminFormatToolbarEndToEndIT {

    private final DocumentInstancePdfRenderer renderer = new DocumentInstancePdfRenderer();
    private final CanonicalHtmlProfileCorrector corrector =
            new CanonicalHtmlProfileCorrector(new com.fasterxml.jackson.databind.ObjectMapper());

    /**
     * A formatting profile of the shape {@code DocxFormattingExtractor}
     * stores for any DOCX-uploaded template: 1in page margins and a list
     * level indented 0.25in with a -0.25in hanging first line. These are
     * exactly the values the corrector would re-assert over an admin's
     * edit if the marker were missing, so every assertion below that
     * rejects them is proving the marker works.
     */
    private String profileJson() {
        return """
            {"version":1,
             "page":{"widthIn":{"twips":12240,"inches":8.5,"points":612.0},
                     "heightIn":{"twips":15840,"inches":11.0,"points":792.0},
                     "orientation":"portrait",
                     "margins":{"top":{"twips":1440,"inches":1.0,"points":72.0},
                                "right":{"twips":1440,"inches":1.0,"points":72.0},
                                "bottom":{"twips":1440,"inches":1.0,"points":72.0},
                                "left":{"twips":1440,"inches":1.0,"points":72.0}}},
             "lists":{"1":{"abstractNumId":"1","levels":[
                        {"levelIndex":0,"format":"bullet","text":"\\u2022",
                         "indentLeft":{"twips":360,"inches":0.25,"points":18.0},
                         "indentFirstLine":{"twips":-360,"inches":-0.25,"points":-18.0}}]}}}
            """;
    }

    /** The REAL save path, in the order saveSchema runs it. */
    private String save(String canvasHtml) {
        String normalized = XhtmlNormalizer.toXhtmlFragment(canvasHtml);
        String corrected = corrector.correct(normalized, profileJson());
        return CanonicalHtmlSanitizer.sanitize(corrected);
    }

    /**
     * Canvas HTML shaped like docx-preview's output, with the toolbar's
     * edits applied the way the frontend applies them — inline longhand
     * declarations plus a {@code data-fmt-admin} marker on each element
     * whose layout the admin deliberately set.
     *
     * @param sectionStyle  page section style (page margins live here)
     * @param sectionMarked whether the admin set page margins
     * @param alphaStyle    first body paragraph's style
     * @param alphaMarked   whether the admin formatted it
     * @param alphaRunStyle first body paragraph's run style (font size)
     * @param charlieStyle  list paragraph's style (indent)
     * @param charlieMarked whether the admin formatted the list item
     */
    private static String canvas(String sectionStyle, boolean sectionMarked,
                                 String alphaStyle, boolean alphaMarked,
                                 String alphaRunStyle,
                                 String charlieStyle, boolean charlieMarked) {
        String mark = " data-fmt-admin=\"1\"";
        return ""
            + "<section class=\"docx\" style=\"" + sectionStyle + "\""
            + (sectionMarked ? mark : "") + ">"
            + "<header style=\"margin-top: calc(0.5in - 1in); min-height: 0.5in;\">"
            + "<p><span class=\"docx_r_h\">ANVICORP HEADERMARK</span></p></header>"
            + "<article class=\"docx\">"
            + "<p class=\"docx_Normal\" style=\"" + alphaStyle + "\""
            + (alphaMarked ? mark : "") + ">"
            + "<span class=\"docx_r_1\" style=\"" + alphaRunStyle + "\">"
            + "ALPHA the first paragraph is deliberately long so that it wraps onto at "
            + "least a second and probably a third rendered line which is what makes "
            + "line spacing measurable from glyph positions in the output.</span></p>"
            + "<p class=\"docx_Normal\"><span class=\"docx_r_2\">BRAVOWORD</span></p>"
            + "<p class=\"docx-num-1-0\" style=\"" + charlieStyle + "\""
            + (charlieMarked ? mark : "") + ">"
            + "<span class=\"docx_r_3\">CHARLIEWORD list item one</span></p>"
            + "<p class=\"docx_Normal\"><span class=\"docx_r_5\">ECHOWORD final.</span></p>"
            + "</article>"
            + "<footer><p><span class=\"docx_r_f\">ANVICORP FOOTERMARK</span></p></footer>"
            + "</section>";
    }

    private static final String PAD_1IN =
        "width: 8.5in; min-height: 11in; padding-top: 1in; padding-right: 1in; "
        + "padding-bottom: 1in; padding-left: 1in;";

    /** Untouched document: no admin edits, no markers. */
    private static String baselineCanvas() {
        return canvas(PAD_1IN, false, "", false, "", "", false);
    }

    /** save() then render() — the full journey a real edit takes. */
    private byte[] savedAndRendered(String canvasHtml) {
        return renderer.renderToPdf(save(canvasHtml), Map.of(), Map.of(), "E2E");
    }

    // ── PDF glyph measurement ────────────────────────────────────────

    private record Glyph(String ch, float x, float y, float size) {}

    private static List<Glyph> glyphs(byte[] pdf) throws Exception {
        List<Glyph> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition p : positions) {
                        out.add(new Glyph(p.getUnicode(), p.getXDirAdj(),
                                p.getYDirAdj(), p.getFontSizeInPt()));
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        return out;
    }

    private static Optional<Glyph> find(List<Glyph> gs, String word) {
        for (int i = 0; i + word.length() <= gs.size(); i++) {
            boolean hit = true;
            for (int j = 0; j < word.length(); j++) {
                if (!gs.get(i + j).ch().equals(String.valueOf(word.charAt(j)))) {
                    hit = false;
                    break;
                }
            }
            if (hit) return Optional.of(gs.get(i));
        }
        return Optional.empty();
    }

    private static float xOf(List<Glyph> gs, String word) {
        return find(gs, word).orElseThrow(
                () -> new AssertionError("marker not rendered: " + word)).x();
    }

    private static float yOf(List<Glyph> gs, String word) {
        return find(gs, word).orElseThrow(
                () -> new AssertionError("marker not rendered: " + word)).y();
    }

    /** Distinct wrapped-line y positions of the ALPHA paragraph. */
    private static List<Float> alphaLineYs(List<Glyph> gs) {
        int start = -1;
        for (int i = 0; i + 5 <= gs.size(); i++) {
            if (gs.get(i).ch().equals("A") && gs.get(i + 1).ch().equals("L")
                    && gs.get(i + 2).ch().equals("P") && gs.get(i + 3).ch().equals("H")
                    && gs.get(i + 4).ch().equals("A")) { start = i; break; }
        }
        List<Float> ys = new ArrayList<>();
        if (start < 0) return ys;
        for (int i = start; i < gs.size(); i++) {
            Glyph g = gs.get(i);
            if (g.ch().equals("B") && i + 4 < gs.size()
                    && gs.get(i + 1).ch().equals("R") && gs.get(i + 2).ch().equals("A")
                    && gs.get(i + 3).ch().equals("V")) break;
            if (ys.isEmpty() || (g.y() > ys.get(ys.size() - 1) + 1.5f)) ys.add(g.y());
        }
        return ys;
    }

    // ── 1. Line spacing ──────────────────────────────────────────────

    @Test
    void lineSpacingReachesTheExecutedPdf() throws Exception {
        List<Float> base = alphaLineYs(glyphs(savedAndRendered(baselineCanvas())));
        List<Float> wide = alphaLineYs(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "line-height: 2;", true, "", "", false))));
        assertTrue(base.size() >= 2 && wide.size() >= 2, "ALPHA must wrap");
        float baseGap = base.get(1) - base.get(0);
        float wideGap = wide.get(1) - wide.get(0);
        assertTrue(wideGap > baseGap * 1.4f,
                "line-height:2 must widen the wrapped line gap through save+render: "
                        + baseGap + " -> " + wideGap);
    }

    // ── 2. Paragraph spacing before / after ──────────────────────────

    /**
     * Measured between two values that both sit ABOVE the neighbouring
     * block's own margin, so the 36pt difference is the control's effect
     * alone. Comparing against 0 would measure CSS margin COLLAPSING —
     * {@code max(neighbour-bottom, own-top)} — which is correct
     * behaviour, not a failure.
     */
    @Test
    void paragraphSpacingReachesTheExecutedPdf() throws Exception {
        float at36 = yOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "margin-top: 36pt;", true, "", "", false))), "ALPHA");
        float at72 = yOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "margin-top: 72pt;", true, "", "", false))), "ALPHA");
        assertTrue(Math.abs((at72 - at36) - 36f) < 2f,
                "margin-top 36pt->72pt must move the paragraph down exactly 36pt: "
                        + at36 + " -> " + at72);

        float after36 = yOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "margin-bottom: 36pt;", true, "", "", false))), "BRAVOWORD");
        float after72 = yOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "margin-bottom: 72pt;", true, "", "", false))), "BRAVOWORD");
        assertTrue(Math.abs((after72 - after36) - 36f) < 2f,
                "margin-bottom 36pt->72pt must push the NEXT block down exactly 36pt: "
                        + after36 + " -> " + after72);
    }

    // ── 3. Alignment ─────────────────────────────────────────────────

    @Test
    void alignmentReachesTheExecutedPdf() throws Exception {
        float left = xOf(glyphs(savedAndRendered(baselineCanvas())), "ALPHA");
        float centered = xOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "text-align: center;", true, "", "", false))), "ALPHA");
        float right = xOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "text-align: right;", true, "", "", false))), "ALPHA");
        // ALPHA wraps and fills the line, so its FIRST glyph barely moves
        // when centered; BRAVOWORD is a short line and is the honest probe.
        float bLeft = xOf(glyphs(savedAndRendered(baselineCanvas())), "BRAVOWORD");
        assertTrue(centered >= left, "center must not move text left: " + left + " -> " + centered);
        assertTrue(right >= left, "right must not move text left");

        float bCentered = xOf(glyphs(savedAndRendered(canvas(
                PAD_1IN, false, "", false, "", "", false)
                .replace("<p class=\"docx_Normal\"><span class=\"docx_r_2\">BRAVOWORD",
                        "<p class=\"docx_Normal\" style=\"text-align: center;\""
                        + " data-fmt-admin=\"1\"><span class=\"docx_r_2\">BRAVOWORD"))),
                "BRAVOWORD");
        assertTrue(bCentered - bLeft > 100f,
                "text-align:center must visibly center a short paragraph: "
                        + bLeft + " -> " + bCentered);
    }

    // ── 4a. Body-paragraph indent ────────────────────────────────────

    @Test
    void bodyParagraphIndentReachesTheExecutedPdf() throws Exception {
        float base = xOf(glyphs(savedAndRendered(baselineCanvas())), "ALPHA");
        float indented = xOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "margin-left: 1in;", true, "", "", false))), "ALPHA");
        assertTrue(Math.abs((indented - base) - 72f) < 6f,
                "margin-left:1in must indent the paragraph by 72pt: "
                        + base + " -> " + indented);

        float firstLine = xOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "text-indent: 0.5in;", true, "", "", false))), "ALPHA");
        assertTrue(Math.abs((firstLine - base) - 36f) < 6f,
                "text-indent:0.5in must indent the FIRST line by 36pt: "
                        + base + " -> " + firstLine);
    }

    // ── 4b. List-paragraph indent — WAS REVERTED BY THE CORRECTOR ────

    /**
     * The regression this whole marker mechanism exists for. Without
     * {@code data-fmt-admin}, {@code correctListIndent} strips the
     * admin's {@code margin-left} and re-applies the profile's 0.25in on
     * every save, so the edit never reached the renderer.
     */
    @Test
    void listParagraphIndentSurvivesTheCorrectorAndReachesThePdf() throws Exception {
        float base = xOf(glyphs(savedAndRendered(baselineCanvas())), "CHARLIEWORD");
        float indented = xOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "", false, "", "margin-left: 2in;", true))),
                "CHARLIEWORD");
        assertTrue(indented - base > 80f,
                "admin's 2in list indent must survive the corrector and reach the PDF: "
                        + base + " -> " + indented);

        // The marker is what makes the difference: the SAME edit without
        // it must be reverted to the profile's indent.
        float unmarked = xOf(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "", false, "", "margin-left: 2in;", false))),
                "CHARLIEWORD");
        assertTrue(Math.abs(unmarked - base) < 20f,
                "an UNMARKED list paragraph must still be corrected to the profile "
                        + "(proving the marker, not something else, is doing the work): "
                        + base + " vs " + unmarked);
    }

    // ── 5. Font size ─────────────────────────────────────────────────

    @Test
    void fontSizeReachesTheExecutedPdf() throws Exception {
        float base = find(glyphs(savedAndRendered(baselineCanvas())), "ALPHA")
                .orElseThrow().size();
        float bigger = find(glyphs(savedAndRendered(
                canvas(PAD_1IN, false, "", true, "font-size: 20pt;", "", false))), "ALPHA")
                .orElseThrow().size();
        assertTrue(bigger > base * 1.4f,
                "font-size:20pt must render visibly larger: " + base + " -> " + bigger);
    }

    // ── 6. Page margins — WAS REVERTED BY THE CORRECTOR ──────────────

    /**
     * The other regression. Without the marker,
     * {@code correctPageGeometry} overwrote all four of the admin's
     * padding longhands with the profile's 1in on every save, so
     * {@code preparePageGeometry} scraped 1in and the executed
     * {@code @page margin} never changed.
     */
    @Test
    void pageMarginsSurviveTheCorrectorAndReachThePdf() throws Exception {
        String tight =
            "width: 8.5in; min-height: 11in; padding-top: 1.5in; padding-right: 0.5in; "
            + "padding-bottom: 0.5in; padding-left: 0.5in;";

        String savedHtml = save(canvas(tight, true, "", false, "", "", false));
        assertTrue(savedHtml.contains("padding-top: 1.5in")
                        || savedHtml.contains("padding-top:1.5in"),
                "admin's page margins must survive the save pipeline: " + savedHtml);

        // The composed @page rule the renderer builds from the SAVED html.
        String shell = renderer.toXhtmlForTest("E2E", savedHtml);
        assertTrue(shell.contains("margin: 1.5in 0.5in 0.5in 0.5in"),
                "executed @page margin must match the admin's edit, not the "
                        + "profile's 1in: " + shell.substring(shell.indexOf("@page"),
                        Math.min(shell.indexOf("@page") + 120, shell.length())));

        // And measured out of the rendered page.
        List<Glyph> base = glyphs(savedAndRendered(baselineCanvas()));
        List<Glyph> moved = glyphs(savedAndRendered(canvas(tight, true, "", false, "", "", false)));
        assertTrue(Math.abs((xOf(base, "ALPHA") - xOf(moved, "ALPHA")) - 36f) < 8f,
                "left margin 1in->0.5in must move content 36pt left: "
                        + xOf(base, "ALPHA") + " -> " + xOf(moved, "ALPHA"));
        assertTrue(Math.abs((yOf(moved, "ALPHA") - yOf(base, "ALPHA")) - 36f) < 10f,
                "top margin 1in->1.5in must move content 36pt down: "
                        + yOf(base, "ALPHA") + " -> " + yOf(moved, "ALPHA"));

        // Marker absent -> corrector reverts to the profile. Proves the
        // marker is load-bearing rather than the edit surviving anyway.
        String unmarked = save(canvas(tight, false, "", false, "", "", false));
        String unmarkedShell = renderer.toXhtmlForTest("E2E", unmarked);
        assertTrue(unmarkedShell.contains("margin: 1.00in 1.00in 1.00in 1.00in"),
                "an UNMARKED section must still be corrected to the profile: "
                        + unmarkedShell.substring(unmarkedShell.indexOf("@page"),
                        Math.min(unmarkedShell.indexOf("@page") + 120, unmarkedShell.length())));
    }

    // ── Invariants that ride along ───────────────────────────────────

    @Test
    void markerPersistsThroughSaveAndHeaderFooterStillRepeat() throws Exception {
        String saved = save(canvas(
            "width: 8.5in; min-height: 11in; padding-top: 1.5in; padding-right: 0.5in; "
            + "padding-bottom: 0.5in; padding-left: 0.5in;", true,
            "line-height: 2; margin-top: 36pt; text-align: center;", true,
            "font-size: 20pt;", "margin-left: 2in;", true));

        // The marker MUST survive the sanitizer — the corrector re-reads
        // it on the NEXT save, so a stripped marker would mean the edit
        // silently reverts the second time the admin hits Save.
        assertTrue(saved.contains("data-fmt-admin"),
                "sanitizer must preserve the override marker: " + saved);

        // The studio-only visual outline must NOT be here; the frontend
        // strips it before serialising. (Guard: if it ever leaked, the
        // sanitizer would happily persist it.)
        assertTrue(!saved.contains("studio-fmt-active"),
                "visual highlight class must never reach canonical HTML: " + saved);

        List<Glyph> gs = glyphs(renderer.renderToPdf(saved, Map.of(), Map.of(), "E2E"));
        assertTrue(find(gs, "HEADERMARK").isPresent(), "header must still render");
        assertTrue(find(gs, "FOOTERMARK").isPresent(), "footer must still render");
    }
}
