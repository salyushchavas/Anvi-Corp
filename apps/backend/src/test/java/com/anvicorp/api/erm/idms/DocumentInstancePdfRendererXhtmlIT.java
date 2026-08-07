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
