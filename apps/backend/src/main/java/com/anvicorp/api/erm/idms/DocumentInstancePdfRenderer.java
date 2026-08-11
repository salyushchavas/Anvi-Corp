package com.anvicorp.api.erm.idms;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

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
 */
@Component
@Slf4j
public class DocumentInstancePdfRenderer {

    private static final int RENDER_TIMEOUT_SECONDS = 45;

    /**
     * ANVI header logo — loaded once at construction time from
     * {@code /idms/anvi-header-logo.png} on the classpath and embedded as
     * a base64 data URL in the PDF's running header. The source template
     * (ANVI_OPT unpaid) carries this exact asset in {@code header2.xml};
     * hardcoding it here (rather than round-tripping through the DOCX
     * import) keeps the header uniform across every generated PDF
     * regardless of which template variant an admin uploads.
     */
    private final String anviHeaderLogoDataUrl = loadLogoDataUrl();

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

    private static String loadLogoDataUrl() {
        try (InputStream in = new ClassPathResource("idms/anvi-header-logo.png").getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            // Non-fatal — the PDF still renders body + footer without
            // the logo. A missing classpath resource is a packaging bug,
            // not a per-render failure; log once at load and continue.
            LoggerHolder.LOG.warn("[IDMS] anvi header logo missing from classpath: {}", e.getMessage());
            return null;
        }
    }

    /** Static-init logger holder — avoids @Slf4j field access from a static method. */
    private static final class LoggerHolder {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(DocumentInstancePdfRenderer.class);
    }

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

    /** Wraps the filled body HTML in a minimal XHTML doc with print CSS
     *  matching the docx-preview canvas — same font-family + margins so the
     *  executed PDF reads visually equivalent to the studio preview. */
    private String wrapInHtmlDoc(String title, String bodyHtml) {
        String logoImg = anviHeaderLogoDataUrl == null
                ? ""
                : "<img src=\"" + anviHeaderLogoDataUrl
                        + "\" alt=\"ANVI\" style=\"width:287px;height:75px;\" />";
        return ""
                + "<!DOCTYPE html>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head>"
                + "<meta charset=\"UTF-8\" />"
                + "<title>" + escapeHtml(title == null ? "Document" : title) + "</title>"
                + "<style>"
                // Source of truth — ANVI_OPT unpaid template DOCX:
                //   Page: A4 (8.27in × 11.69in)
                //   Margins: LEFT 1.0in, RIGHT 0.88in, TOP 1.0in, BOTTOM 1.0in
                //   Header: ANVI logo at 0.08in from page top
                //   Footer: address block at 0.12in from page bottom
                // Prior config was `letter, 20mm` which over-inset the
                // body ~0.21in on top/bottom + 0.12in on right (Letter is
                // narrower than A4). The mismatch made the finalized PDF
                // read visibly cramped vs the source template.
                + "  @page { size: A4; margin: 1in 0.88in 1in 1in;"
                + "          @top-center { content: element(anviHeader);"
                + "                        vertical-align: top;"
                + "                        padding-top: 0.08in; }"
                + "          @bottom-center { content: element(anviFooter);"
                + "                        vertical-align: bottom;"
                + "                        padding-bottom: 0.12in; } }"
                // WIDTH SAFETY — docx-preview emits an outer
                // <article class=\"docx\"> and per-page <section> wrappers
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
                // Source template (ANVI_OPT unpaid) uses Times New Roman
                // 12pt for body; the fallback matches so a legacy
                // template without an explicit stylesheet still lands on
                // the same face the author intended.
                + "  body { font-family: 'Times New Roman', Times, serif;"
                + "         font-size: 12pt; color: #111;"
                + "         word-wrap: break-word;"
                + "         overflow-wrap: break-word; }"
                + "  article, section, div, article.docx, section.docx {"
                + "    width: auto !important; max-width: 100% !important;"
                + "    box-sizing: border-box; }"
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
                + "  header, footer { display: block; color: #555; font-size: 9pt; }"
                // openhtmltopdf running elements — hoisted OUT of the
                // body flow and INTO the @page's @top-center / @bottom-
                // center margin boxes above. The elements below live at
                // the top of the body but never render there; the
                // renderer clones them into each page's margin. `running(x)`
                // is CSS3 Paged Media (see openhtmltopdf docs).
                + "  .pdf-anvi-header { position: running(anviHeader);"
                + "    text-align: center; margin: 0; padding: 0; }"
                + "  .pdf-anvi-header img { display: inline-block;"
                + "    vertical-align: top; }"
                + "  .pdf-anvi-footer { position: running(anviFooter);"
                + "    text-align: center; font-size: 9pt; color: #333;"
                + "    line-height: 1.3; margin: 0; padding: 0; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<div class=\"pdf-anvi-header\">" + logoImg + "</div>"
                + "<div class=\"pdf-anvi-footer\">"
                + "Address: 7950 Legacy Dr, Suite 400, Plano, TX, 75024"
                + "<br />"
                + "Phone # 913-297-7493, Email: info@anvicorp.com"
                + "</div>"
                + sanitiseForXhtml(bodyHtml)
                + "</body>"
                + "</html>";
    }

    /**
     * openhtmltopdf's XHTML SAX parser is stricter than a browser: any
     * unclosed block tag (e.g. a bare {@code <p>...} the docx-preview
     * canvas emitted) crashes the render with SAXParseException. The
     * old regex-based sanitiser could self-close void tags but could
     * not repair mis-nested / unclosed block structure — that's what
     * this instance was hitting.
     *
     * <p>Delegates to {@link XhtmlNormalizer#toXhtmlFragment(String)},
     * which parses the fragment with jsoup's HTML5-lenient parser
     * (auto-closes tags per the tag-closing rules) and re-emits as
     * XML syntax so every void self-closes and every open tag has a
     * matching end tag. Applied to the FULLY interpolated body —
     * signature {@code <img>} elements and field-text spans go in raw
     * and come out well-formed on the way to openhtmltopdf.</p>
     */
    private String sanitiseForXhtml(String html) {
        return XhtmlNormalizer.toXhtmlFragment(html);
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
