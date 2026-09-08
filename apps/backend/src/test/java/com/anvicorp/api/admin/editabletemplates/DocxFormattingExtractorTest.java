package com.anvicorp.api.admin.editabletemplates;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1 regression suite for {@link DocxFormattingExtractor}.
 *
 * <p>The suite builds fixture DOCX bytes IN-MEMORY with POI itself so the
 * tests are hermetic (no binary fixture files to check in and no risk of
 * drift when Word rewrites round-tripped documents). The flagship test —
 * {@link #captures_negative_header_indent_that_docx_preview_drops} —
 * builds a header whose paragraph carries the exact {@code w:ind
 * w:left="-1587"} shape that produced the "header shifts right in the
 * finalized PDF" bug and asserts the extractor reads it back correctly.
 * If this test ever regresses, the header-alignment fix loses its data
 * source.</p>
 */
class DocxFormattingExtractorTest {

    private final DocxFormattingExtractor extractor =
            new DocxFormattingExtractor(new ObjectMapper());

    /**
     * THE Stage 1 flagship test — proves POI reads the negative header
     * indent that docx-preview drops. This is the exact XML shape the
     * ANVI offer letter template uses ({@code w:ind w:left="-1587"}
     * ≈ -0.11 inch, hanging the header into the left margin). The
     * whole metadata layer exists so consumers can read this value
     * back and stop guessing about header alignment.
     */
    @Test
    void captures_negative_header_indent_that_docx_preview_drops() throws Exception {
        byte[] docxBytes = buildFixtureWithNegativeIndentHeader();
        FormattingProfile profile = extractor.extractProfile(docxBytes);

        assertNotNull(profile, "profile must not be null on well-formed DOCX");
        assertNotNull(profile.header(), "header profile must be present");
        assertFalse(profile.header().paragraphs().isEmpty(),
                "header must have at least one paragraph");

        FormattingProfile.ParagraphProfile headerPara =
                profile.header().paragraphs().get(0);
        FormattingProfile.Indent indent = headerPara.indent();
        assertNotNull(indent, "indent must be captured");
        assertNotNull(indent.left(),
                "left indent must not be null when set (even to a negative value)");
        assertEquals(-1587L, indent.left().twips(),
                "negative left indent lost — this is exactly the docx-preview drop this layer fixes");
        assertEquals(-1587 / 1440.0, indent.left().inches(), 1e-6,
                "inches conversion drifted");
        assertEquals(-1587 / 20.0, indent.left().points(), 1e-6,
                "points conversion drifted");
    }

    /** Page geometry — margins + size + orientation captured from
     *  {@code sectPr}. */
    @Test
    void captures_page_geometry_from_sectPr() throws Exception {
        byte[] docxBytes = buildFixtureWithPageGeometry();
        FormattingProfile profile = extractor.extractProfile(docxBytes);

        assertNotNull(profile);
        FormattingProfile.PageGeometry page = profile.page();
        assertNotNull(page, "page geometry captured");
        assertNotNull(page.widthIn());
        assertNotNull(page.heightIn());
        // Letter page (12240 × 15840 twips = 8.5 × 11 in).
        assertEquals(12240L, page.widthIn().twips());
        assertEquals(15840L, page.heightIn().twips());
        assertEquals("portrait", page.orientation());
        // 1 inch = 1440 twips
        assertNotNull(page.margins().top());
        assertEquals(1440L, page.margins().top().twips(),
                "top margin should be 1 inch = 1440 twips");
        assertEquals(1440L, page.margins().left().twips());
        assertEquals(720L, page.margins().headerDistance().twips(),
                "header distance 0.5 in = 720 twips");
    }

    /** Version stamp — consumers rely on it to detect schema changes. */
    @Test
    void stamps_profile_version() throws Exception {
        byte[] docxBytes = buildFixtureWithPageGeometry();
        FormattingProfile profile = extractor.extractProfile(docxBytes);
        assertNotNull(profile);
        assertEquals(DocxFormattingExtractor.PROFILE_VERSION, profile.version());
    }

    /** No header — profile carries null header, doesn't crash. */
    @Test
    void handles_no_header_footer_gracefully() throws Exception {
        byte[] docxBytes = buildFixtureBodyOnly();
        FormattingProfile profile = extractor.extractProfile(docxBytes);
        assertNotNull(profile, "profile still built from a body-only DOCX");
        assertNull(profile.header(), "header null when absent");
        assertNull(profile.footer(), "footer null when absent");
    }

    /** Garbage bytes — extractor returns null, does NOT throw. This is
     *  the critical fail-open contract: upload MUST continue to succeed
     *  regardless of whether the profile could be extracted. */
    @Test
    void returns_null_on_malformed_input() {
        byte[] garbage = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04 };
        // extract() returns the JSON string; on failure it returns null.
        assertNull(extractor.extract(garbage),
                "extract must return null (not throw) on malformed input");
        assertNull(extractor.extractProfile(garbage),
                "extractProfile must return null on malformed input");
    }

    /** Empty input — same fail-open contract. */
    @Test
    void returns_null_on_empty_input() {
        assertNull(extractor.extract(new byte[0]));
        assertNull(extractor.extractProfile(new byte[0]));
        assertNull(extractor.extract(null));
        assertNull(extractor.extractProfile(null));
    }

    /** Extract → serialize → the JSON is well-formed and carries the
     *  captured header indent. Round-trips through the same ObjectMapper
     *  the service uses so we know the persisted JSONB is consistent
     *  with what tests inspect. */
    @Test
    void serialises_profile_to_json_with_captured_data() throws Exception {
        byte[] docxBytes = buildFixtureWithNegativeIndentHeader();
        String json = extractor.extract(docxBytes);
        assertNotNull(json, "JSON serialisation must succeed");
        assertTrue(json.contains("\"version\":" + DocxFormattingExtractor.PROFILE_VERSION),
                "profile version must be present in JSON: " + json);
        assertTrue(json.contains("-1587"),
                "negative indent twips must be present in JSON: " + json);
    }

    // ── Stage 3 — numbering geometry + list typography ───────────────

    /**
     * ⭐ THE Stage 3 flagship. The ANVI duty list is
     * {@code w:ind w:left="1080" w:hanging="360"}. This pins BOTH the
     * capture and the normalisation the corrector depends on: Word's
     * POSITIVE {@code w:hanging} is stored as a NEGATIVE first-line
     * indent, so a consumer can drop the two values straight into CSS
     * {@code margin-left} / {@code text-indent} without re-deriving the
     * sign. If this normalisation ever flips, bullets render with the
     * marker pushed right instead of hanging left.
     */
    @Test
    void captures_list_level_indent_with_hanging_as_negative_first_line()
            throws Exception {
        byte[] docxBytes = buildFixtureWithBulletList();
        FormattingProfile profile = extractor.extractProfile(docxBytes);

        assertNotNull(profile);
        assertFalse(profile.lists().isEmpty(),
                "the numbering definition the body actually uses must be captured");

        FormattingProfile.ListDefinition def =
                profile.lists().values().iterator().next();
        assertNotNull(def.levels());
        assertFalse(def.levels().isEmpty(), "level 0 must be captured");

        FormattingProfile.LevelDefinition level = def.levels().get(0);
        assertEquals(0, level.levelIndex());
        assertNotNull(level.indentLeft(), "w:left must be captured");
        assertEquals(1080L, level.indentLeft().twips(),
                "left indent lost — the corrector's margin-left has no source");
        assertEquals(0.75, level.indentLeft().inches(), 1e-6,
                "1080 twips = 0.75in");

        assertNotNull(level.indentFirstLine(), "w:hanging must be captured");
        assertEquals(-360L, level.indentFirstLine().twips(),
                "hanging MUST be normalised to a negative first-line indent — "
                        + "the corrector maps it straight onto text-indent");
        assertEquals(-0.25, level.indentFirstLine().inches(), 1e-6,
                "-360 twips = -0.25in");
    }

    /**
     * ⭐ The mis-probe fix, at its source. The fixture is shaped like a
     * real offer letter: a Calibri date line FIRST, then Times New Roman
     * bullets. {@code extractBodyDefault} walks document order, so it
     * latches onto Calibri — and that Calibri is what used to be
     * injected into the bullets. The per-list font must be tallied from
     * the list's OWN runs and come back Times New Roman.
     */
    @Test
    void sources_list_font_from_the_lists_own_runs_not_the_letterhead()
            throws Exception {
        byte[] docxBytes = buildFixtureWithBulletList();
        FormattingProfile profile = extractor.extractProfile(docxBytes);

        assertNotNull(profile);
        assertEquals("Calibri", profile.bodyDefault().fontFamily(),
                "precondition: the body-default probe still latches onto the "
                        + "date line — this is exactly why lists need their own");

        FormattingProfile.ListDefinition def =
                profile.lists().values().iterator().next();
        assertEquals("Times New Roman", def.fontFamily(),
                "the list font must come from the numbered paragraphs' runs, "
                        + "NOT from the letterhead the body-default probe found");
    }

    /**
     * END-TO-END through the real pipeline: source DOCX → extractor →
     * profile JSON → corrector → corrected HTML. Proves the whole chain
     * closes, not just the two halves in isolation.
     *
     * <p>The input HTML is the shape docx-preview actually emits — a
     * list {@code <p>} carrying the {@code docx-num-<numId>-<ilvl>}
     * class and NO horizontal geometry whatsoever. That absence is the
     * bug. After the corrector runs, the paragraph must carry the
     * source document's own hanging indent and font.</p>
     */
    @Test
    void end_to_end_docx_to_corrected_html_restores_indent_and_font()
            throws Exception {
        byte[] docxBytes = buildFixtureWithBulletList();
        FormattingProfile profile = extractor.extractProfile(docxBytes);
        assertNotNull(profile);
        String numId = profile.lists().keySet().iterator().next();
        String profileJson = extractor.extract(docxBytes);
        assertNotNull(profileJson);

        // Exactly what docx-preview hands us: class, text, no geometry.
        String before = "<section class=\"docx\">"
                + "<p class=\"docx-num-" + numId + "-0\">Collect, clean, "
                + "preprocess, and analyze structured and unstructured data "
                + "for use in AI/ML applications.</p>"
                + "</section>";

        assertFalse(before.contains("margin-left"),
                "precondition: docx-preview emitted no indent — the defect");

        CanonicalHtmlProfileCorrector corrector =
                new CanonicalHtmlProfileCorrector(new ObjectMapper());
        String after = corrector.correct(before, profileJson);

        assertTrue(after.contains("margin-left:0.75in"),
                "end-to-end: the source's 1080tw indent must reach the HTML: "
                        + after);
        assertTrue(after.contains("text-indent:-0.25in"),
                "end-to-end: the source's 360tw hanging must reach the HTML: "
                        + after);
        assertTrue(after.contains("font-family:Times New Roman"),
                "end-to-end: the LIST's font must reach the HTML: " + after);
        assertFalse(after.contains("font-family:Calibri"),
                "end-to-end: the letterhead font must not reach the bullets: "
                        + after);
    }

    // ── Fixture builders ─────────────────────────────────────────────

    /**
     * Build a DOCX with a header paragraph whose left indent is
     * {@code -1587} twips (≈ -0.11 inch) — the exact shape from the
     * ANVI offer letter that docx-preview drops. Also carries a body
     * paragraph so the document is well-formed.
     */
    private byte[] buildFixtureWithNegativeIndentHeader() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            // Body paragraph so the doc has at least one body element.
            XWPFParagraph body = doc.createParagraph();
            XWPFRun bodyRun = body.createRun();
            bodyRun.setText("Body");
            bodyRun.setFontFamily("Times New Roman");
            bodyRun.setFontSize(11);

            // Header with a negative left indent.
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph headerPara = header.createParagraph();
            headerPara.setAlignment(ParagraphAlignment.LEFT);
            // POI's setIndentationLeft takes twips as int (raw XML value).
            headerPara.setIndentationLeft(-1587);
            XWPFRun headerRun = headerPara.createRun();
            headerRun.setText("Company header");
            headerRun.setFontFamily("Calibri");
            headerRun.setFontSize(11);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Build a DOCX with a section that has explicit page size + margin
     * values (Letter, 1-inch margins, 0.5-in header distance).
     */
    private byte[] buildFixtureWithPageGeometry() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("Body");

            // Set the section properties manually via the CT-level API —
            // POI doesn't expose a high-level setter for page geometry.
            CTSectPr sectPr = doc.getDocument().getBody().isSetSectPr()
                    ? doc.getDocument().getBody().getSectPr()
                    : doc.getDocument().getBody().addNewSectPr();

            CTPageSz pgSz = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
            pgSz.setW(BigInteger.valueOf(12240));   // 8.5 in
            pgSz.setH(BigInteger.valueOf(15840));   // 11 in
            pgSz.setOrient(STPageOrientation.PORTRAIT);

            CTPageMar pgMar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
            pgMar.setTop(BigInteger.valueOf(1440));
            pgMar.setBottom(BigInteger.valueOf(1440));
            pgMar.setLeft(BigInteger.valueOf(1440));
            pgMar.setRight(BigInteger.valueOf(1440));
            pgMar.setHeader(BigInteger.valueOf(720));
            pgMar.setFooter(BigInteger.valueOf(720));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Body-only DOCX — no header, no footer. Extractor MUST return a
     * profile with {@code null} header / footer without crashing.
     */
    private byte[] buildFixtureBodyOnly() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("Just a body paragraph");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Build a DOCX shaped like the ANVI offer letter's duty list: a
     * Calibri date line FIRST (so the body-default probe latches onto
     * it, reproducing the mis-probe), then Times New Roman bullets on a
     * numbering definition carrying {@code w:ind w:left="1080"
     * w:hanging="360"} — the exact geometry docx-preview drops.
     */
    private byte[] buildFixtureWithBulletList() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            // Letterhead-ish lead paragraph in a DIFFERENT font. This is
            // the run extractBodyDefault finds first, and the font that
            // used to get injected into the bullets.
            XWPFParagraph lead = doc.createParagraph();
            XWPFRun leadRun = lead.createRun();
            leadRun.setText("Date: 08/24/2026");
            leadRun.setFontFamily("Calibri");
            leadRun.setFontSize(11);

            // Numbering definition: bullet, left 1080tw, hanging 360tw.
            XWPFNumbering numbering = doc.createNumbering();
            CTAbstractNum ctAbstractNum = CTAbstractNum.Factory.newInstance();
            ctAbstractNum.setAbstractNumId(BigInteger.ZERO);
            CTLvl lvl = ctAbstractNum.addNewLvl();
            lvl.setIlvl(BigInteger.ZERO);
            lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
            lvl.addNewLvlText().setVal("•");
            CTInd ind = lvl.addNewPPr().addNewInd();
            ind.setLeft(BigInteger.valueOf(1080));
            ind.setHanging(BigInteger.valueOf(360));

            BigInteger abstractNumId = numbering.addAbstractNum(
                    new XWPFAbstractNum(ctAbstractNum));
            BigInteger numId = numbering.addNum(abstractNumId);

            // Three duties — including the FIRST one, which is the item
            // that reads as un-bulleted in the broken preview.
            for (String duty : List.of(
                    "Design, develop, and implement machine learning models.",
                    "Collect, clean, preprocess, and analyze data.",
                    "Develop, train, test, and validate models.")) {
                XWPFParagraph p = doc.createParagraph();
                p.setNumID(numId);
                XWPFRun r = p.createRun();
                r.setText(duty);
                r.setFontFamily("Times New Roman");
                r.setFontSize(12);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
