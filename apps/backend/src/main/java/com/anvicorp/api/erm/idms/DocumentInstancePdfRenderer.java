package com.anvicorp.api.erm.idms;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDMS Phase 2 — turns a filled canonical HTML + field-value map into a
 * PDF via openhtmltopdf. The DOCX-preview canvas + this renderer share
 * the same anchor contract: {@code <span data-field-id="…" class="doc-field">}
 * spans get their text content replaced with the filled value (or the
 * signature image, for signature-type fields).
 *
 * <h2>Bounded executor</h2>
 * openhtmltopdf's builder is thread-safe per invocation but CPU-heavy; if
 * two requests hit it at the same moment on a small Railway box we OOM
 * (the survey guardrail). All renders funnel through a single-thread
 * executor with a 45-second per-render timeout. If the queue backs up,
 * new callers wait — better than tipping the JVM over. Operator note in
 * the report: {@code JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75}.
 *
 * <h2>Signature image handling</h2>
 * Signature fields carry the image bytes as a data URL that we embed via
 * a bare {@code <img src="data:image/png;base64,…" />} inside the anchor
 * span — openhtmltopdf's PDFBox backend renders base64 image sources
 * natively.
 *
 * <h2>Generic per-document page geometry + header/footer</h2>
 * docx-preview emits each source-DOCX page as a {@code <section class="docx"
 * style="padding:…; width:…; min-height:…;">} with the DOCX's OWN page
 * margins baked into the inline padding, AND emits the DOCX's own header
 * and footer as inline {@code <header>} / {@code <footer>} elements
 * (N copies for N pages). If we naively add {@code @page margin} on top,
 * the effective inset doubles (@page margin + section padding both apply);
 * if we hardcode a running header, it double-renders on top of the docx-
 * preview inline header AND overrides the source document's own branding.
 *
 * <p>{@link #preparePageGeometry(String)} handles both — it scrapes the
 * first {@code section.docx}'s padding + width + min-height into an
 * effective {@code @page} size + margin, strips those inline properties
 * so the section becomes a plain container, extracts the first
 * {@code <header>} / {@code <footer>} and marks them
 * {@code position: running(docHeader / docFooter)} so openhtmltopdf hoists
 * them into the {@code @top-center / @bottom-center} margin boxes on every
 * page. Extra header/footer copies are removed so nothing stacks. The
 * outer {@code @page} then references {@code element(docHeader)} +
 * {@code element(docFooter)} by GENERIC names — no per-document hardcoding,
 * works for any uploaded template (offer letter, NDA, W-4, empty header,
 * etc.).</p>
 */
@Component
@Slf4j
public class DocumentInstancePdfRenderer {

    private static final int RENDER_TIMEOUT_SECONDS = 45;

    /** Fallbacks when the canonical HTML has no {@code section.docx} to
     *  scrape geometry from (small hand-authored templates, unit tests,
     *  or a docx-preview variant that emits a different wrapper shape). */
    private static final String DEFAULT_PAGE_SIZE = "A4";
    private static final String DEFAULT_PAGE_MARGIN = "1in 0.88in 1in 1in";
    private static final String DEFAULT_PAGE_MARGIN_LEFT = "1in";

    /** Simple CSS length shape — number + unit. Accept only what a docx-
     *  preview render actually produces (in / cm / mm / pt / px). We
     *  never pass through arbitrary CSS to {@code @page} because the
     *  scraped value ends up in the rendered stylesheet unquoted. */
    private static final Pattern SIMPLE_LENGTH = Pattern.compile(
            "^-?\\d+(?:\\.\\d+)?(?:in|cm|mm|pt|px)$");

    /** Single-thread executor with a named factory so heap dumps are legible. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicLong seq = new AtomicLong(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "idms-pdf-renderer-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * Interpolate the anchor spans with their filled values (or signature
     * images) and render to a PDF byte array.
     *
     * @param canonicalHtml the studio's serialised HTML (with anchor spans)
     * @param textByFieldId text values keyed by fieldId — empty string for
     *                      un-answered ANY optional field
     * @param signatureDataUrlByFieldId  base64 PNG data URLs keyed by fieldId
     *                      for signature fields
     * @param title         document title, used in the PDF metadata
     */
    public byte[] renderToPdf(String canonicalHtml,
                              Map<String, String> textByFieldId,
                              Map<String, String> signatureDataUrlByFieldId,
                              String title) {
        // Hoist neighbouring docx-preview run's font-family / font-size
        // onto each anchor BEFORE interpolation so the filled value
        // inherits the same typography as the surrounding template
        // text. See IdmsFieldFontInheritance for the walk order.
        String canonicalWithInheritedFonts =
                IdmsFieldFontInheritance.applyToCanonicalHtml(canonicalHtml);
        String filledBody = interpolate(
                canonicalWithInheritedFonts, textByFieldId, signatureDataUrlByFieldId);
        String fullDoc = wrapInHtmlDoc(title, filledBody);

        Future<byte[]> f = executor.submit(() -> {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.useFastMode();
                builder.withHtmlContent(fullDoc, /*baseUri*/ null);
                builder.toStream(out);
                builder.run();
                return out.toByteArray();
            }
        });
        try {
            return f.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            throw new IllegalStateException(
                    "PDF render timed out after " + RENDER_TIMEOUT_SECONDS + "s.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[IDMS] PDF render failed: {}", cause.getMessage(), cause);
            throw new RuntimeException("PDF render failed: " + cause.getMessage(), cause);
        }
    }

    /**
     * Package-visible for unit tests. Replaces every anchor span's inner
     * content with the filled value or signature image; unfilled anchors
     * keep their placeholder text (schema-drift-tolerant).
     *
     * <p><b>Why jsoup rather than a regex.</b> The prior
     * {@code Pattern <span … data-field-id="…" …>(.*?)</span>} used a
     * non-greedy tail, which truncates at the FIRST inner {@code </span>}.
     * docx-preview wraps every text run in its own {@code <span
     * class="docx_r_…">}, so any anchor with a styled run inside — a
     * {@code content_block} wrapping a paragraph / list, or a text/date
     * anchor wrapping a placeholder that docx split into multiple runs —
     * had its match end at the first inner close, not the anchor's. All
     * content BEYOND that first inner close (subsequent bullet items,
     * additional runs) leaked past the substitution and rendered in the
     * PDF ALONGSIDE the filled value. That was the "Job Duties appears
     * twice" bug and (when the placeholder was multi-run) the "start
     * date shows two values" bug.</p>
     *
     * <p>jsoup selects {@code span[data-field-id]} and rewrites the
     * element's children — the parser knows where the outer anchor
     * ends, so nothing leaks regardless of nesting depth.</p>
     */
    String interpolate(String canonicalHtml,
                       Map<String, String> textByFieldId,
                       Map<String, String> signatureDataUrlByFieldId) {
        if (canonicalHtml == null || canonicalHtml.isEmpty()) return "";
        Document doc = Jsoup.parseBodyFragment(canonicalHtml);
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .prettyPrint(false)
                .charset("UTF-8");
        for (Element anchor : doc.select("span[data-field-id]")) {
            String fieldId = anchor.attr("data-field-id");
            String signature = signatureDataUrlByFieldId == null
                    ? null : signatureDataUrlByFieldId.get(fieldId);
            if (signature != null && !signature.isBlank()) {
                anchor.empty();
                Element img = anchor.appendElement("img");
                img.attr("style",
                        "max-height:1.6em;vertical-align:baseline;display:inline-block;");
                img.attr("src", signature);
                continue;
            }
            String txt = textByFieldId == null ? null : textByFieldId.get(fieldId);
            if (txt == null || txt.isEmpty()) {
                // Unfilled optional anchor — keep the placeholder text
                // exactly like the prior implementation did.
                continue;
            }
            anchor.empty();
            // content_block values are multi-line (Job Duties bullets,
            // paragraphs). openhtmltopdf collapses \n like any browser
            // does when white-space is default, so a multi-line value
            // would render as one wall of text. Convert every newline
            // to a <br /> so line breaks survive; text/date values
            // never contain \n so this is a no-op for them and we avoid
            // a per-field-type branch in the interpolator.
            String[] lines = txt.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) anchor.appendElement("br");
                if (!lines[i].isEmpty()) anchor.appendChild(new TextNode(lines[i]));
            }
        }
        return doc.body().html();
    }

    /**
     * Result of {@link #preparePageGeometry(String)} — the transformed
     * body plus the effective page geometry pulled from the source doc.
     */
    static final class PreparedDoc {
        final String bodyHtml;
        final String pageSize;
        final String pageMargin;
        /** Left @page margin as a bare CSS length (e.g. "1in") — kept
         *  separate from the concatenated {@link #pageMargin} so the
         *  header running element can offset itself by exactly this
         *  amount and extend to the physical page left edge. */
        final String pageMarginLeft;
        final boolean hasHeader;
        final boolean hasFooter;
        PreparedDoc(String bodyHtml, String pageSize, String pageMargin,
                    String pageMarginLeft, boolean hasHeader, boolean hasFooter) {
            this.bodyHtml = bodyHtml;
            this.pageSize = pageSize;
            this.pageMargin = pageMargin;
            this.pageMarginLeft = pageMarginLeft;
            this.hasHeader = hasHeader;
            this.hasFooter = hasFooter;
        }
    }

    /**
     * Package-visible for unit tests. Walks the body HTML and:
     *
     * <ol>
     *   <li>Scrapes {@code padding-top/right/bottom/left} + {@code width} +
     *       {@code min-height} off the first {@code section.docx} —
     *       docx-preview bakes the source DOCX's page geometry there. If
     *       present + all four margins parse cleanly as a simple CSS
     *       length ({@link #SIMPLE_LENGTH}), those become the effective
     *       {@code @page} size + margin. Otherwise we fall back to the
     *       {@link #DEFAULT_PAGE_SIZE} / {@link #DEFAULT_PAGE_MARGIN} —
     *       sensible defaults for a hand-authored template that has no
     *       docx-preview wrapper at all.</li>
     *   <li>Strips those same length properties off EVERY {@code section.docx}
     *       (there are N of them for N source pages, all with the same
     *       geometry). Without this the effective margin was
     *       {@code @page margin + section padding} — the double-margin
     *       bug the survey traced.</li>
     *   <li>Marks the FIRST {@code <header>} with
     *       {@code position: running(docHeader)} and deletes any additional
     *       copies. Same for {@code <footer>} → {@code running(docFooter)}.
     *       openhtmltopdf lifts those elements out of body flow and paints
     *       them into the {@code @top-center / @bottom-center} margin boxes
     *       on every generated page. Because we take the DOCUMENT's own
     *       header/footer (whatever it contains — logo, address text, page
     *       number, nothing), the result is GENERIC across any uploaded
     *       template with no hardcoded branding.</li>
     * </ol>
     *
     * <p>Also strips the inline {@code margin-top} / {@code min-height} the
     * docx-preview render pass added to the header/footer — those values
     * only made sense inside the section's padding area and would render
     * weirdly inside a page-margin box.</p>
     */
    PreparedDoc preparePageGeometry(String bodyHtml) {
        if (bodyHtml == null || bodyHtml.isEmpty()) {
            return new PreparedDoc("", DEFAULT_PAGE_SIZE, DEFAULT_PAGE_MARGIN,
                    DEFAULT_PAGE_MARGIN_LEFT, false, false);
        }
        Document doc = Jsoup.parseBodyFragment(bodyHtml);
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .prettyPrint(false)
                .charset("UTF-8");

        // Scrape geometry from the first section.docx; strip padding /
        // width / min-height off every section so @page is the single
        // source of page geometry.
        String pageSize = DEFAULT_PAGE_SIZE;
        String pageMargin = DEFAULT_PAGE_MARGIN;
        String pageMarginLeft = DEFAULT_PAGE_MARGIN_LEFT;
        Elements sections = doc.select("section.docx");
        if (!sections.isEmpty()) {
            Element first = sections.first();
            String style = first.attr("style");
            String pt = extractInlineLength(style, "padding-top");
            String pr = extractInlineLength(style, "padding-right");
            String pb = extractInlineLength(style, "padding-bottom");
            String pl = extractInlineLength(style, "padding-left");
            if (pt != null && pr != null && pb != null && pl != null) {
                pageMargin = pt + " " + pr + " " + pb + " " + pl;
                pageMarginLeft = pl;
            }
            String w = extractInlineLength(style, "width");
            String h = extractInlineLength(style, "min-height");
            if (w != null && h != null) {
                pageSize = w + " " + h;
            }
            for (Element s : sections) {
                String cleaned = stripInlineProperties(s.attr("style"),
                        "padding-left", "padding-right", "padding-top",
                        "padding-bottom", "width", "min-height");
                if (cleaned.isEmpty()) s.removeAttr("style");
                else s.attr("style", cleaned);
            }
        }

        // Hoist first <header> to a running element, drop the rest.
        Elements headers = doc.select("header");
        boolean hasHeader = !headers.isEmpty();
        if (hasHeader) {
            Element first = headers.first();
            first.addClass("pdf-doc-header");
            // Also strip the docx-preview inline margin-top / min-height —
            // those anchored the header inside the section padding
            // (which no longer exists) and would render as dead space
            // inside the page margin box.
            String cleaned = stripInlineProperties(first.attr("style"),
                    "margin-top", "min-height");
            if (cleaned.isEmpty()) first.removeAttr("style");
            else first.attr("style", cleaned);
            for (int i = 1; i < headers.size(); i++) {
                headers.get(i).remove();
            }
        }

        // Same for <footer>.
        Elements footers = doc.select("footer");
        boolean hasFooter = !footers.isEmpty();
        if (hasFooter) {
            Element first = footers.first();
            first.addClass("pdf-doc-footer");
            String cleaned = stripInlineProperties(first.attr("style"),
                    "margin-bottom", "min-height");
            if (cleaned.isEmpty()) first.removeAttr("style");
            else first.attr("style", cleaned);
            for (int i = 1; i < footers.size(); i++) {
                footers.get(i).remove();
            }
        }

        return new PreparedDoc(doc.body().html(), pageSize, pageMargin,
                pageMarginLeft, hasHeader, hasFooter);
    }

    /**
     * Match a single inline CSS declaration by property name and return
     * its value (trimmed) iff it parses as {@link #SIMPLE_LENGTH}.
     * Returns null when absent, empty, or not a simple length — the
     * caller falls back to defaults rather than piping arbitrary CSS
     * into the {@code @page} rule.
     */
    static String extractInlineLength(String styleAttr, String prop) {
        if (styleAttr == null || styleAttr.isEmpty()) return null;
        Pattern p = Pattern.compile(
                "(?i)(?:^|;)\\s*" + Pattern.quote(prop) + "\\s*:\\s*([^;]+?)\\s*(?:;|$)");
        Matcher m = p.matcher(styleAttr);
        if (!m.find()) return null;
        String v = m.group(1).trim().toLowerCase(Locale.ROOT);
        return SIMPLE_LENGTH.matcher(v).matches() ? v : null;
    }

    /**
     * Return the style attribute with the named declarations removed.
     * Case-insensitive. Preserves all other declarations. Cleans up any
     * doubled or trailing {@code ;} that removal leaves behind.
     */
    static String stripInlineProperties(String styleAttr, String... props) {
        if (styleAttr == null || styleAttr.isEmpty()) return "";
        String out = styleAttr;
        for (String prop : props) {
            Pattern p = Pattern.compile(
                    "(?i)(?:^|;)\\s*" + Pattern.quote(prop) + "\\s*:[^;]*(?:;|$)");
            out = p.matcher(out).replaceAll(";");
        }
        return out.replaceAll(";+", ";")
                .replaceAll("^\\s*;\\s*", "")
                .replaceAll("\\s*;\\s*$", "")
                .trim();
    }

    /** Wraps the filled body HTML in a minimal XHTML doc with print CSS.
     *  Page geometry (size + margins) + running header / footer are all
     *  sourced from the uploaded document's own docx-preview output via
     *  {@link #preparePageGeometry(String)} — no per-template hardcoding. */
    private String wrapInHtmlDoc(String title, String bodyHtml) {
        PreparedDoc prep = preparePageGeometry(bodyHtml);
        // Only emit an @top-left / @bottom-center margin box when the
        // document actually carries a header / footer — an empty
        // element() reference on openhtmltopdf still consumes vertical
        // space in the margin, which would push the body down for
        // no reason on a template with no header at all.
        //
        // Header uses @top-left (not @top-center) so the box anchors
        // to the LEFT part of the top margin band. Combined with the
        // negative margin-left on .pdf-doc-header (below) this pulls
        // the header's left edge past the page-margin boundary and
        // out to the physical page left edge — matching the source
        // DOCX's <w:ind w:left="-1587"/> (~-1.1in) negative paragraph
        // indent, which does the same thing in Word.
        String topBox = prep.hasHeader
                ? "@top-left { content: element(docHeader);"
                        + " vertical-align: top; }"
                : "";
        String bottomBox = prep.hasFooter
                ? "@bottom-center { content: element(docFooter);"
                        + " vertical-align: bottom; }"
                : "";
        return ""
                + "<!DOCTYPE html>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head>"
                + "<meta charset=\"UTF-8\" />"
                + "<title>" + escapeHtml(title == null ? "Document" : title) + "</title>"
                + "<style>"
                // Page geometry — sourced from the uploaded document's
                // own docx-preview page section. Fallback A4 +
                // 1in/0.88in/1in/1in when the input has no section
                // wrapper (hand-authored template, unit tests).
                + "  @page { size: " + prep.pageSize + ";"
                + "          margin: " + prep.pageMargin + ";"
                + "          " + topBox + " " + bottomBox + " }"
                // WIDTH SAFETY — docx-preview emits an outer
                // <article class="docx"> and per-page <section> wrappers
                // that carry an explicit fixed width matching the source
                // document's page (e.g. width:21cm for A4 originals, or
                // width:8.5in for Letter). When that fixed width exceeds
                // the PDF's printable area, openhtmltopdf renders the
                // wrapper at its declared width and everything past the
                // right margin is clipped off the page — the "text runs
                // off the right edge" bug.
                // Overriding width + max-width on those wrappers forces
                // them into the printable area; box-sizing keeps padding
                // from re-inflating them; word-wrap: break-word rescues
                // long unbreakable tokens (emails, URLs) that would
                // otherwise still poke past the margin because they're a
                // single word with no whitespace to break at.
                + "  html, body { width: 100%; max-width: 100%; margin: 0; padding: 0; }"
                // Body fallback font — the docx-preview <style> block
                // (now preserved by CanonicalHtmlSanitizer since a1fa486)
                // sets the actual per-run font via class rules, and those
                // win via CSS cascade order because they appear in the
                // body AFTER this <head> block. This body rule only
                // applies to text OUTSIDE the .docx wrapper (in practice
                // none) or as a last-resort fallback when a template's
                // stylesheet doesn't declare a font at all.
                //
                // Times New Roman 12pt is the modal fallback across the
                // legacy offer templates in play; a docx-preview stylesheet
                // (when present) still wins per class rules.
                + "  body { font-family: 'Times New Roman', Times, serif;"
                + "         font-size: 12pt; color: #111;"
                + "         word-wrap: break-word;"
                + "         overflow-wrap: break-word; }"
                // Defence-in-depth for the double-margin bug —
                // preparePageGeometry strips inline padding / width /
                // min-height off every section.docx, but a future docx-
                // preview variant that emits the same rules via a
                // different attribute shape would slip past the string
                // strip. The !important CSS override catches whatever
                // the strip missed.
                + "  article, section, div, article.docx, section.docx {"
                + "    width: auto !important; max-width: 100% !important;"
                + "    box-sizing: border-box; }"
                + "  article.docx, section.docx {"
                + "    padding: 0 !important; min-height: 0 !important; }"
                + "  p, li { margin: 0 0 8pt; line-height: 1.4;"
                + "          word-wrap: break-word; overflow-wrap: break-word; }"
                // Real <ul>/<ol>/<li> lists — a template that doesn't route
                // through docx-preview (custom-authored, or a future editor)
                // still gets sensible indentation + a visible marker + item
                // breathing room instead of the bare browser default which
                // openhtmltopdf under-renders.
                + "  ul, ol { margin: 6pt 0; padding-left: 2.5em; }"
                + "  ul { list-style-type: disc; }"
                + "  ol { list-style-type: decimal; }"
                + "  li { margin: 0 0 4pt; padding-left: 0.25em; }"
                // docx-preview list-item pattern — a Word list becomes a
                // <p class="docx-num-{id}-{lvl}"> carrying inline rules
                // `display: list-item; list-style-position: inside;` from
                // its injected <style>. `inside` crams the bullet into the
                // text flow with no gap ("•Item" instead of "•  Item"),
                // and the paragraph itself has no left indent so the block
                // starts flush with the body — that's the "cramped bullets
                // jammed together" complaint. Flipping to `outside` +
                // margin-left gives the bullet its own column of space and
                // a visible indent, matching how Word renders a real list.
                // The `!important` beats the injected inline rule (same
                // specificity otherwise), and `list-style-position` is a
                // safe override because the marker rendering itself still
                // works either way — only its position changes.
                + "  p[class*=\"docx-num\"] {"
                + "    list-style-position: outside !important;"
                + "    margin-left: 2em !important;"
                + "    padding-left: 0.5em !important;"
                + "    margin-top: 2pt; margin-bottom: 4pt;"
                + "  }"
                + "  h1, h2, h3, h4 { font-weight: bold; margin: 12pt 0 6pt; }"
                + "  table { border-collapse: collapse; max-width: 100%; }"
                + "  td, th { padding: 4pt 6pt; word-wrap: break-word;"
                + "           overflow-wrap: break-word; }"
                + "  .doc-field { display: inline; word-wrap: break-word;"
                + "               overflow-wrap: break-word; }"
                // Signature image sizing — em-based so the signature scales
                // with the surrounding text's line-height instead of
                // dominating it. The prior absolute 40px pushed line height
                // ~3× on 11pt body text and broke the signature row into two
                // lines when the signature was placed on an underscore blank
                // ("Signed: __________"). 1.6em keeps the signature clearly
                // visible while sitting within normal line flow;
                // vertical-align: baseline puts the image ON the baseline
                // (matches how handwriting sits on a signature line).
                + "  .doc-field img { max-height: 1.6em; max-width: 100%;"
                + "                   vertical-align: baseline;"
                + "                   display: inline-block; }"
                // Base header/footer typography — openhtmltopdf strips
                // these from body flow via the running-element rules
                // below, but keep the default text style just in case a
                // fallback path renders them inline (e.g. a preview that
                // doesn't paginate).
                + "  header, footer { display: block; color: #555; font-size: 9pt; }"
                // Running-element hoist — openhtmltopdf lifts each
                // marked element OUT of body flow and INTO the @page
                // margin-box that references it via element(name). See
                // the openhtmltopdf CSS3 Paged Media docs.
                //
                // Header {@code margin-left: -{pageMarginLeft}} pulls
                // the content out of the @top-left box's left boundary
                // (which sits at x = pageMarginLeft from the physical
                // page edge) back to x = 0, so a wide logo starts flush
                // with the paper's left edge instead of the body's left
                // margin. Peer of Word's negative paragraph indent that
                // authors reach for when they want a masthead to escape
                // the body inset. Only emitted when the document actually
                // has a header, so a plain no-header template keeps a
                // clean stylesheet.
                + (prep.hasHeader
                        ? "  .pdf-doc-header { position: running(docHeader);"
                                + "    margin-left: -" + prep.pageMarginLeft + "; }"
                        : "")
                + (prep.hasFooter
                        ? "  .pdf-doc-footer { position: running(docFooter); }"
                        : "")
                + "</style>"
                + "</head>"
                + "<body>"
                + prep.bodyHtml
                + "</body>"
                + "</html>";
    }

    /** For fully-controlled substitution — the studio's raw text passes
     *  through unmodified but we still need to guard the four XML metachars
     *  so a curly apostrophe or an inline "&" doesn't break the parser. */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Test-only helper — decode a base64 data URL to raw bytes. */
    @SuppressWarnings("unused")
    static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return new byte[0];
        int comma = dataUrl.indexOf(',');
        String payload = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
        try { return Base64.getDecoder().decode(payload); }
        catch (Exception e) { return new byte[0]; }
    }

    /** Test hook — returns the raw HTML the renderer would have shipped to
     *  openhtmltopdf. Kept package-private. */
    String toXhtmlForTest(String title, String bodyHtml) {
        return wrapInHtmlDoc(title, bodyHtml);
    }

    /** Not used at runtime — silences the unused-import warning on {@code List}
     *  in future extensions. */
    @SuppressWarnings("unused")
    private static void referenceList(List<?> l) { /* no-op */ }

    /** Not used at runtime — kept to silence the unused-import warning on
     *  {@code ByteArrayInputStream}, which we may bring back if the render
     *  path ever needs to stream from an in-memory buffer. */
    @SuppressWarnings("unused")
    private static ByteArrayInputStream referenceStream(byte[] b) {
        return new ByteArrayInputStream(b);
    }

    /** Not used at runtime — future overloads for charset control. */
    @SuppressWarnings("unused")
    private static void referenceCharset() {
        StandardCharsets.UTF_8.name();
    }
}
