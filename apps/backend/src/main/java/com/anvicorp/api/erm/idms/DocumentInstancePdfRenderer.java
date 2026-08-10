package com.anvicorp.api.erm.idms;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 */
@Component
@Slf4j
public class DocumentInstancePdfRenderer {

    private static final int RENDER_TIMEOUT_SECONDS = 45;

    /** Matches every anchor span the studio emits: {@code <span … data-field-id="uuid" …>…</span>}. */
    private static final Pattern FIELD_SPAN =
            Pattern.compile("<span([^>]*?)data-field-id=\"([^\"]+)\"([^>]*)>(.*?)</span>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

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
     * Package-visible for unit tests. Replaces every anchor span's contents
     * with the filled value or signature image; unmatched spans render as
     * their original text (defensive against a schema/HTML drift).
     */
    String interpolate(String canonicalHtml,
                       Map<String, String> textByFieldId,
                       Map<String, String> signatureDataUrlByFieldId) {
        if (canonicalHtml == null || canonicalHtml.isEmpty()) return "";
        Matcher m = FIELD_SPAN.matcher(canonicalHtml);
        StringBuilder sb = new StringBuilder(canonicalHtml.length() + 256);
        int cursor = 0;
        while (m.find()) {
            sb.append(canonicalHtml, cursor, m.start());
            String beforeAttrs = m.group(1);
            String fieldId = m.group(2);
            String afterAttrs = m.group(3);
            String originalInner = m.group(4);

            String replacement;
            String signature = signatureDataUrlByFieldId == null
                    ? null : signatureDataUrlByFieldId.get(fieldId);
            if (signature != null && !signature.isBlank()) {
                replacement =
                        "<img style=\"max-height:40px;vertical-align:middle;\" src=\""
                                + escapeHtml(signature) + "\" />";
            } else {
                String txt = textByFieldId == null ? null : textByFieldId.get(fieldId);
                if (txt == null || txt.isEmpty()) {
                    replacement = originalInner; // leave the placeholder text
                } else {
                    // content_block values are multi-line (Job Duties bullets,
                    // paragraphs). openhtmltopdf collapses \n like any browser
                    // does when white-space is default, so a multi-line value
                    // would render as one wall of text. Convert every newline
                    // to a <br /> so line breaks survive; text/date values
                    // never contain \n so this is a no-op for them and we
                    // avoid a per-field-type branch in the interpolator.
                    replacement = escapeHtml(txt).replace("\n", "<br />");
                }
            }
            sb.append("<span")
              .append(beforeAttrs)
              .append("data-field-id=\"")
              .append(escapeHtml(fieldId))
              .append("\"")
              .append(afterAttrs)
              .append(">")
              .append(replacement)
              .append("</span>");
            cursor = m.end();
        }
        sb.append(canonicalHtml, cursor, canonicalHtml.length());
        return sb.toString();
    }

    /** Wraps the filled body HTML in a minimal XHTML doc with print CSS
     *  matching the docx-preview canvas — same font-family + margins so the
     *  executed PDF reads visually equivalent to the studio preview. */
    private String wrapInHtmlDoc(String title, String bodyHtml) {
        return ""
                + "<!DOCTYPE html>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head>"
                + "<meta charset=\"UTF-8\" />"
                + "<title>" + escapeHtml(title == null ? "Document" : title) + "</title>"
                + "<style>"
                + "  @page { size: A4; margin: 24mm 20mm; }"
                + "  body { font-family: 'Times New Roman', serif; font-size: 11pt; color: #111; }"
                + "  p, li { margin: 0 0 8pt; line-height: 1.4; }"
                + "  h1, h2, h3, h4 { font-weight: bold; margin: 12pt 0 6pt; }"
                + "  table { border-collapse: collapse; }"
                + "  td, th { padding: 4pt 6pt; }"
                + "  .doc-field { display: inline; }"
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
                + "</style>"
                + "</head>"
                + "<body>"
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
