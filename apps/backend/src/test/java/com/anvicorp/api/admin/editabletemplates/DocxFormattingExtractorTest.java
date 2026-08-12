package com.anvicorp.api.admin.editabletemplates;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

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
}
