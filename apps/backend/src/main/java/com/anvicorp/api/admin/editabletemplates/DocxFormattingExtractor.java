package com.anvicorp.api.admin.editabletemplates;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts a {@link FormattingProfile} from a DOCX file's raw bytes using
 * Apache POI's XWPF (OOXML) API.
 *
 * <p><b>Why this exists.</b> Every font / bullet / margin / header bug we
 * hit in IDMS traced back to trusting docx-preview's lossy DOCX→HTML
 * conversion instead of reading the source document. The authoritative
 * data lives in the DOCX XML — this extractor reads that data directly
 * and returns a structured profile the studio prep + PDF renderer can
 * consume as source of truth.</p>
 *
 * <h2>Fail-open by design</h2>
 * {@link #extract(byte[])} NEVER throws. A malformed / non-DOCX input,
 * a POI parse blow-up, an XML schema surprise — all return {@code null}
 * with a WARN log. Upload MUST continue to succeed; the profile is a
 * strictly additive enrichment. Consumers guard for null and fall back
 * to the current scrape-from-HTML rendering path.
 *
 * <h2>Stage 1 scope</h2>
 * See {@link FormattingProfile} javadoc for the extraction surface.
 * Minimum-useful set: page geometry, primary header/footer paragraphs +
 * runs, body typography defaults, list definitions. Explicitly deferred
 * to Stage 2: per-body-paragraph properties (would balloon the profile
 * for the median template), tables, borders, tab stops, images,
 * comments, tracked changes, multi-section headers/footers (first-page /
 * even-page variants).
 *
 * <h2>Units</h2>
 * DOCX stores lengths as twips (1/1440 inch, 1/20 pt). Every {@code
 * Length} in the profile carries the raw twips value plus pre-computed
 * inches AND points so consumers pick their CSS unit without arithmetic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocxFormattingExtractor {

    /** The current profile schema version. Bump when the shape changes
     *  in a way consumers must detect. */
    public static final int PROFILE_VERSION = 1;

    private final ObjectMapper objectMapper;

    /**
     * Extract a formatting profile from raw DOCX bytes and serialize it
     * to JSON for storage on the template row.
     *
     * @return the JSON string, or {@code null} if extraction failed
     *         (malformed input, POI blow-up, JSON serialisation failure).
     *         Consumers MUST tolerate {@code null} — upload always succeeds.
     */
    public String extract(byte[] docxBytes) {
        FormattingProfile profile = extractProfile(docxBytes);
        if (profile == null) return null;
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            log.warn("[DocxFormattingExtractor] JSON serialization failed "
                    + "(profile discarded): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Package-visible for tests. Returns the structured profile record
     * before JSON serialization so assertions can inspect the shape
     * directly instead of round-tripping through Jackson.
     */
    FormattingProfile extractProfile(byte[] docxBytes) {
        if (docxBytes == null || docxBytes.length == 0) {
            log.warn("[DocxFormattingExtractor] empty input — returning null profile");
            return null;
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            FormattingProfile.PageGeometry page = extractPageGeometry(doc);
            FormattingProfile.HeaderFooterProfile header = extractHeader(doc);
            FormattingProfile.HeaderFooterProfile footer = extractFooter(doc);
            FormattingProfile.BodyDefault bodyDefault = extractBodyDefault(doc);
            Map<String, FormattingProfile.ListDefinition> lists = extractLists(doc);
            return new FormattingProfile(
                    PROFILE_VERSION, page, header, footer, bodyDefault, lists);
        } catch (Exception e) {
            log.warn("[DocxFormattingExtractor] extraction failed "
                    + "(returning null profile so upload continues): {}",
                    e.getMessage());
            return null;
        }
    }

    // ── Page geometry ────────────────────────────────────────────────

    private FormattingProfile.PageGeometry extractPageGeometry(XWPFDocument doc) {
        CTSectPr sectPr = findSectPr(doc);
        if (sectPr == null) {
            return new FormattingProfile.PageGeometry(
                    null, null, null,
                    new FormattingProfile.Margins(null, null, null, null, null, null, null));
        }
        CTPageSz sz = sectPr.isSetPgSz() ? sectPr.getPgSz() : null;
        CTPageMar mar = sectPr.isSetPgMar() ? sectPr.getPgMar() : null;

        FormattingProfile.Length widthIn = sz != null && sz.isSetW()
                ? FormattingProfile.Length.fromTwips(toLong(sz.getW())) : null;
        FormattingProfile.Length heightIn = sz != null && sz.isSetH()
                ? FormattingProfile.Length.fromTwips(toLong(sz.getH())) : null;
        String orientation = sz != null && sz.isSetOrient()
                ? sz.getOrient() == STPageOrientation.LANDSCAPE ? "landscape" : "portrait"
                : null;

        // POI's CTPageMar getters return BigInteger directly (no isSet
        // guards on the header/footer/gutter attributes — they return
        // null when the XML omitted them). The twipsFromMarginObject
        // helper handles null gracefully so unspecified header/footer/
        // gutter distances land as null in the profile.
        FormattingProfile.Margins margins = new FormattingProfile.Margins(
                mar != null ? twipsFromMarginObject(mar.getTop()) : null,
                mar != null ? twipsFromMarginObject(mar.getRight()) : null,
                mar != null ? twipsFromMarginObject(mar.getBottom()) : null,
                mar != null ? twipsFromMarginObject(mar.getLeft()) : null,
                mar != null ? twipsFromMarginObject(mar.getHeader()) : null,
                mar != null ? twipsFromMarginObject(mar.getFooter()) : null,
                mar != null ? twipsFromMarginObject(mar.getGutter()) : null);
        return new FormattingProfile.PageGeometry(widthIn, heightIn, orientation, margins);
    }

    /** Walk body elements backwards to find the last section-properties
     *  block (Word's convention: the doc's terminal sectPr is the
     *  effective one for a single-section template). Falls back to the
     *  document-level {@code CTBody.getSectPr()} which POI exposes via
     *  {@link XWPFDocument#getDocument()}. */
    private CTSectPr findSectPr(XWPFDocument doc) {
        try {
            if (doc.getDocument() != null
                    && doc.getDocument().getBody() != null
                    && doc.getDocument().getBody().isSetSectPr()) {
                return doc.getDocument().getBody().getSectPr();
            }
        } catch (Exception ignored) { /* fall through to null */ }
        return null;
    }

    /** POI exposes page-margin values as either primitive {@code long}
     *  (top/bottom/left/right — always set on a valid pgMar) or
     *  {@code BigInteger} (header/footer/gutter — optional). Both paths
     *  end up in twips; this helper normalises. */
    private FormattingProfile.Length twipsFromMarginObject(Object v) {
        if (v == null) return null;
        long twips;
        if (v instanceof BigInteger bi) twips = bi.longValueExact();
        else if (v instanceof Long l) twips = l;
        else if (v instanceof Number n) twips = n.longValue();
        else {
            try { twips = Long.parseLong(v.toString()); }
            catch (NumberFormatException e) { return null; }
        }
        return FormattingProfile.Length.fromTwips(twips);
    }

    private static long toLong(Object v) {
        if (v instanceof BigInteger bi) return bi.longValueExact();
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    // ── Header + footer ──────────────────────────────────────────────

    private FormattingProfile.HeaderFooterProfile extractHeader(XWPFDocument doc) {
        List<XWPFHeader> headers = doc.getHeaderList();
        if (headers == null || headers.isEmpty()) return null;
        // Stage 1 — first header only. Multi-variant (first-page /
        // even-page) is Stage 2 when a consumer needs it.
        XWPFHeader h = headers.get(0);
        List<FormattingProfile.ParagraphProfile> paragraphs = new ArrayList<>();
        for (XWPFParagraph p : h.getParagraphs()) {
            paragraphs.add(extractParagraph(p));
        }
        return new FormattingProfile.HeaderFooterProfile("default", paragraphs);
    }

    private FormattingProfile.HeaderFooterProfile extractFooter(XWPFDocument doc) {
        List<XWPFFooter> footers = doc.getFooterList();
        if (footers == null || footers.isEmpty()) return null;
        XWPFFooter f = footers.get(0);
        List<FormattingProfile.ParagraphProfile> paragraphs = new ArrayList<>();
        for (XWPFParagraph p : f.getParagraphs()) {
            paragraphs.add(extractParagraph(p));
        }
        return new FormattingProfile.HeaderFooterProfile("default", paragraphs);
    }

    // ── Paragraph + runs ─────────────────────────────────────────────

    /**
     * Walk a single paragraph's properties + runs. Negative indent values
     * are preserved verbatim — the whole point of this layer is that
     * docx-preview drops the negative left-indent on the offer letter
     * header and we don't want to repeat that mistake.
     */
    private FormattingProfile.ParagraphProfile extractParagraph(XWPFParagraph p) {
        String styleId = p.getStyleID();
        String alignment = alignmentString(p.getAlignment());

        // Indent — POI's getIndentation{Left,Right,FirstLine} returns int
        // twips; -1 means unspecified. Convert -1 to null so the profile
        // distinguishes "not set" from "set to 0" from "set to -1587".
        FormattingProfile.Indent indent = new FormattingProfile.Indent(
                nullableTwips(p.getIndentationLeft()),
                nullableTwips(p.getIndentationRight()),
                nullableTwips(p.getIndentationFirstLine()),
                nullableTwips(p.getIndentationHanging()));

        // Spacing — similarly guards -1.
        FormattingProfile.Spacing spacing = new FormattingProfile.Spacing(
                nullableTwips(p.getSpacingBefore()),
                nullableTwips(p.getSpacingAfter()),
                nullableTwips(p.getSpacingBetween() > 0 ? (int) p.getSpacingBetween() : -1),
                p.getSpacingLineRule() != null
                        ? p.getSpacingLineRule().toString().toLowerCase() : null);

        // List membership — POI's getNumID / getNumIlvl returns BigInteger
        // (nullable). numId also references a concrete numbering def in
        // numbering.xml; the profile's listItem flag is convenience.
        BigInteger numId = p.getNumID();
        BigInteger numIlvl = p.getNumIlvl();
        boolean listItem = numId != null;

        List<FormattingProfile.RunProfile> runs = new ArrayList<>();
        for (XWPFRun r : p.getRuns()) {
            runs.add(extractRun(r));
        }
        return new FormattingProfile.ParagraphProfile(
                styleId, alignment, indent, spacing,
                listItem,
                numId != null ? numId.toString() : null,
                numIlvl != null ? numIlvl.intValue() : null,
                p.getText(),
                runs);
    }

    private FormattingProfile.RunProfile extractRun(XWPFRun r) {
        String fontFamily = r.getFontFamily();
        // POI's getFontSize returns points as int (loses fractional
        // half-points); getFontSizeAsDouble carries the fractional part
        // in newer POI versions. Prefer the fractional path.
        Double fontSizePt = null;
        Integer fontSizeHalfPt = null;
        try {
            double sz = r.getFontSizeAsDouble();
            if (sz > 0) {
                fontSizePt = sz;
                fontSizeHalfPt = (int) Math.round(sz * 2);
            }
        } catch (NoSuchMethodError | Exception ignored) {
            int sz = r.getFontSize();
            if (sz > 0) {
                fontSizePt = (double) sz;
                fontSizeHalfPt = sz * 2;
            }
        }
        String color = r.getColor();
        Boolean bold = r.isBold() ? Boolean.TRUE : null;
        Boolean italic = r.isItalic() ? Boolean.TRUE : null;
        return new FormattingProfile.RunProfile(
                r.text(), fontFamily, fontSizeHalfPt, fontSizePt, bold, italic, color);
    }

    // ── Body defaults ────────────────────────────────────────────────

    /**
     * Stage 1 — best-effort body defaults derived from the FIRST body
     * paragraph. POI 5.2.5 keeps {@code XWPFDefaultRunStyle.getRPr()} /
     * {@code XWPFDefaultParagraphStyle.getPPr()} package-private, and the
     * DocDefaults sub-schemas surface {@code CTPPrGeneral} rather than
     * the {@code CTPPr} XmlBeans generates for a run's own properties —
     * dropping to the raw schema types risks version-brittleness. Reading
     * the first body paragraph's font-family / size / alignment gives a
     * pragmatic approximation that's almost always what the doc author
     * intended as the "default" body typography. Stage 2 can widen this
     * to walk the doc-defaults hierarchy when a consumer demands it.
     */
    private FormattingProfile.BodyDefault extractBodyDefault(XWPFDocument doc) {
        String fontFamily = null;
        Double fontSizePt = null;
        String alignment = null;
        try {
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (alignment == null) {
                    alignment = alignmentString(p.getAlignment());
                }
                for (XWPFRun r : p.getRuns()) {
                    if (fontFamily == null && r.getFontFamily() != null
                            && !r.getFontFamily().isBlank()) {
                        fontFamily = r.getFontFamily();
                    }
                    if (fontSizePt == null) {
                        try {
                            double sz = r.getFontSizeAsDouble();
                            if (sz > 0) fontSizePt = sz;
                        } catch (Exception ignored) {
                            int sz = r.getFontSize();
                            if (sz > 0) fontSizePt = (double) sz;
                        }
                    }
                    if (fontFamily != null && fontSizePt != null) break;
                }
                if (fontFamily != null && fontSizePt != null && alignment != null) break;
            }
        } catch (Exception e) {
            log.debug("[DocxFormattingExtractor] body default probe partial: {}",
                    e.getMessage());
        }
        return new FormattingProfile.BodyDefault(fontFamily, fontSizePt, alignment);
    }

    // ── Numbering / lists ────────────────────────────────────────────

    /**
     * Stage 1 — extract numbering definitions by walking the body's list
     * paragraphs to discover which numIds are actually IN USE, then
     * resolving each via POI's public {@link XWPFNumbering#getNum} +
     * {@link XWPFNumbering#getAbstractNum} wrappers. Skips unused
     * numbering rows (Word ships templates with dozens of legacy list
     * defs); consumers only care about IDs the document actually uses.
     */
    private Map<String, FormattingProfile.ListDefinition> extractLists(XWPFDocument doc) {
        Map<String, FormattingProfile.ListDefinition> out = new LinkedHashMap<>();
        XWPFNumbering numbering = doc.getNumbering();
        if (numbering == null) return out;
        try {
            for (XWPFParagraph p : doc.getParagraphs()) {
                BigInteger numId = p.getNumID();
                if (numId == null) continue;
                String numIdKey = numId.toString();
                if (out.containsKey(numIdKey)) continue;
                BigInteger abstractIdBi = null;
                try {
                    var concrete = numbering.getNum(numId);
                    if (concrete != null && concrete.getCTNum() != null
                            && concrete.getCTNum().getAbstractNumId() != null) {
                        abstractIdBi = concrete.getCTNum().getAbstractNumId().getVal();
                    }
                } catch (Exception ignored) { /* fall through with null */ }
                if (abstractIdBi == null) continue;
                String abstractNumId = abstractIdBi.toString();
                XWPFAbstractNum abs = numbering.getAbstractNum(abstractIdBi);
                if (abs == null || abs.getAbstractNum() == null) continue;
                CTAbstractNum ctAbs = abs.getAbstractNum();
                List<FormattingProfile.LevelDefinition> levels = new ArrayList<>();
                for (CTLvl lvl : ctAbs.getLvlList()) {
                    if (lvl == null) continue;
                    int levelIndex = lvl.getIlvl() != null
                            ? lvl.getIlvl().intValue() : 0;
                    String format = lvl.isSetNumFmt()
                            ? lvl.getNumFmt().getVal().toString() : null;
                    String text = lvl.isSetLvlText() ? lvl.getLvlText().getVal() : null;
                    FormattingProfile.Length leftIn = null;
                    FormattingProfile.Length firstLineIn = null;
                    if (lvl.isSetPPr() && lvl.getPPr().isSetInd()) {
                        var ind = lvl.getPPr().getInd();
                        if (ind.isSetLeft()) {
                            leftIn = FormattingProfile.Length.fromTwips(
                                    toLong(ind.getLeft()));
                        }
                        if (ind.isSetFirstLine()) {
                            firstLineIn = FormattingProfile.Length.fromTwips(
                                    toLong(ind.getFirstLine()));
                        } else if (ind.isSetHanging()) {
                            // Hanging is a positive number that means "the
                            // first line indents LEFT by this much" — store
                            // as negative firstLine so consumers see the
                            // effective indent directly.
                            firstLineIn = FormattingProfile.Length.fromTwips(
                                    -toLong(ind.getHanging()));
                        }
                    }
                    levels.add(new FormattingProfile.LevelDefinition(
                            levelIndex, format, text, leftIn, firstLineIn));
                }
                out.put(numIdKey, new FormattingProfile.ListDefinition(
                        abstractNumId, levels));
            }
        } catch (Exception e) {
            log.debug("[DocxFormattingExtractor] numbering extraction "
                    + "partial: {}", e.getMessage());
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Convert POI's -1-means-unspecified twips int to a Length or null. */
    private static FormattingProfile.Length nullableTwips(int twips) {
        if (twips == -1) return null;
        return FormattingProfile.Length.fromTwips(twips);
    }

    /** Convert POI's {@link ParagraphAlignment} enum to the DOCX
     *  string ({@code left | right | center | both | distribute}). Null
     *  when the paragraph didn't explicitly set alignment. */
    private static String alignmentString(ParagraphAlignment a) {
        if (a == null) return null;
        return switch (a) {
            case LEFT -> "left";
            case RIGHT -> "right";
            case CENTER -> "center";
            case BOTH -> "both";
            case DISTRIBUTE -> "distribute";
            default -> a.name().toLowerCase();
        };
    }
}
