package com.anvicorp.api.erm.idms;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hoists the surrounding docx-preview run's typography onto each
 * {@code <span data-field-id="…">} anchor so the filled value renders
 * in the same font-family + font-size as the neighbouring template text.
 *
 * <p><b>Why we can't rely on plain CSS inheritance.</b> docx-preview
 * emits every text run as a sibling {@code <span>} with an inline
 * {@code style="font-family:…; font-size:…"} rule. The anchor spans
 * (inserted by the studio via {@code Range.surroundContents}) sit either
 * inside a run — in which case cascading works — or between runs at the
 * paragraph level, where cascading falls through to the {@code <body>}
 * default (Times New Roman 11pt in
 * {@link DocumentInstancePdfRenderer}'s wrapInHtmlDoc) and the filled
 * value ends up in a visibly different face / size from the text right
 * beside it.</p>
 *
 * <p>This pass walks each anchor, finds the first non-anchor neighbour
 * with an inline font style (inner descendant → previous sibling → next
 * sibling → parent) and copies its {@code font-family}, {@code font-size},
 * {@code font-weight}, {@code font-style}, {@code letter-spacing},
 * {@code line-height} onto the anchor's own {@code style} attribute.
 * The interpolator then replaces the anchor's inner content with the
 * filled value; the outer style now steers the rendered font so the
 * value is visually indistinguishable from the run beside it.</p>
 *
 * <p><b>Content-block anchors.</b> A {@code content_block} field wraps a
 * whole paragraph, list, or set of block elements. The immediate
 * children of the anchor are then structural blocks
 * ({@code <p>}, {@code <ul>}, {@code <li>}) that docx-preview typically
 * emits WITHOUT inline font styles — the actual {@code font-family}
 * lives on the run {@code <span>}s nested INSIDE those blocks. The
 * original one-level {@code anchor.children()} walk missed them and
 * the anchor fell back to the body {@code Times New Roman 11pt}
 * default, which is exactly the "Job Duties renders in a different
 * font" bug. The walk is therefore a depth-first descent: we recurse
 * into each descendant until we find the first element carrying an
 * inline {@code font-family}. Sibling walk-outs also descend into
 * their subtrees for the same reason.</p>
 *
 * <p>Applied to canonical HTML BEFORE
 * {@link DocumentInstancePdfRenderer#interpolate(String, java.util.Map,
 * java.util.Map)} runs. The frontend live-preview does the equivalent
 * job via {@code getComputedStyle} at paint time in
 * {@code components/idms/InstanceRenderer.tsx}.</p>
 */
@Slf4j
final class IdmsFieldFontInheritance {

    private static final Pattern FONT_FAMILY =
            Pattern.compile("font-family\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FONT_SIZE =
            Pattern.compile("font-size\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FONT_WEIGHT =
            Pattern.compile("font-weight\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FONT_STYLE =
            Pattern.compile("font-style\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LETTER_SPACING =
            Pattern.compile("letter-spacing\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_HEIGHT =
            Pattern.compile("line-height\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);

    private IdmsFieldFontInheritance() {}

    /** Return {@code canonicalHtml} with typography hoisted onto every
     *  field anchor. Never throws — a jsoup parse failure returns the
     *  input unchanged, so this pass is strictly additive. */
    static String applyToCanonicalHtml(String canonicalHtml) {
        if (canonicalHtml == null || canonicalHtml.isEmpty()) return canonicalHtml;
        try {
            Document doc = Jsoup.parseBodyFragment(canonicalHtml);
            doc.outputSettings().prettyPrint(false);
            boolean mutated = false;
            for (Element anchor : doc.select("span[data-field-id]")) {
                String existing = anchor.attr("style");
                if (containsFontFamily(existing) && containsFontSize(existing)) continue;
                String inheritedStyle = resolveFont(anchor);
                if (inheritedStyle == null || inheritedStyle.isEmpty()) continue;
                anchor.attr("style", mergeStyle(existing, inheritedStyle));
                mutated = true;
            }
            if (!mutated) return canonicalHtml;
            return doc.body().html();
        } catch (Exception ex) {
            log.warn("[IDMS] font-inheritance pass failed, using canonical html as-is: {}",
                    ex.getMessage());
            return canonicalHtml;
        }
    }

    private static String resolveFont(Element anchor) {
        String descendant = descendantFont(anchor);
        if (!descendant.isEmpty()) return descendant;
        Element sib = anchor.previousElementSibling();
        while (sib != null) {
            if (!sib.hasAttr("data-field-id")) {
                String s = extractFontDeclarations(sib.attr("style"));
                if (!s.isEmpty()) return s;
                String d = descendantFont(sib);
                if (!d.isEmpty()) return d;
            }
            sib = sib.previousElementSibling();
        }
        sib = anchor.nextElementSibling();
        while (sib != null) {
            if (!sib.hasAttr("data-field-id")) {
                String s = extractFontDeclarations(sib.attr("style"));
                if (!s.isEmpty()) return s;
                String d = descendantFont(sib);
                if (!d.isEmpty()) return d;
            }
            sib = sib.nextElementSibling();
        }
        Element parent = anchor.parent();
        if (parent != null) return extractFontDeclarations(parent.attr("style"));
        return "";
    }

    /** Depth-first descent for the first descendant carrying inline font
     *  declarations. content_block anchors wrap block elements
     *  ({@code <p>}, {@code <ul>}, {@code <li>}) that docx-preview leaves
     *  unstyled; the run {@code <span>}s inside them carry the actual
     *  {@code font-family}. A one-level {@code children()} check misses
     *  those, which was the "Job Duties renders in a different font" bug.
     *  Skips anchor spans so we never inherit from a sibling field's
     *  already-hoisted style. */
    private static String descendantFont(Element el) {
        for (Element child : el.children()) {
            if (child.hasAttr("data-field-id")) continue;
            String s = extractFontDeclarations(child.attr("style"));
            if (!s.isEmpty()) return s;
            String deeper = descendantFont(child);
            if (!deeper.isEmpty()) return deeper;
        }
        return "";
    }

    private static String extractFontDeclarations(String style) {
        if (style == null || style.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        appendMatch(sb, "font-family", FONT_FAMILY, style);
        appendMatch(sb, "font-size", FONT_SIZE, style);
        appendMatch(sb, "font-weight", FONT_WEIGHT, style);
        appendMatch(sb, "font-style", FONT_STYLE, style);
        appendMatch(sb, "letter-spacing", LETTER_SPACING, style);
        appendMatch(sb, "line-height", LINE_HEIGHT, style);
        return sb.toString();
    }

    private static void appendMatch(StringBuilder sb, String prop, Pattern p, String style) {
        Matcher m = p.matcher(style);
        if (m.find()) sb.append(prop).append(':').append(m.group(1).trim()).append(';');
    }

    private static String mergeStyle(String existing, String addition) {
        if (existing == null || existing.isBlank()) return addition;
        String trimmed = existing.trim();
        if (!trimmed.endsWith(";")) trimmed += ";";
        return trimmed + addition;
    }

    private static boolean containsFontFamily(String s) {
        return s != null && FONT_FAMILY.matcher(s).find();
    }

    private static boolean containsFontSize(String s) {
        return s != null && FONT_SIZE.matcher(s).find();
    }
}
