package com.anvicorp.api.erm.idms;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;

/**
 * IDMS Phase 2 — HTML → XHTML normaliser.
 *
 * <p>openhtmltopdf uses a strict SAX-based XHTML parser at render time.
 * It rejects input that a browser (or docx-preview) accepts, including
 * the classic sins that docx-preview commonly emits:</p>
 * <ul>
 *   <li>unclosed block tags (a bare {@code <p>...} with no {@code </p>});</li>
 *   <li>void tags written non-self-closed ({@code <br>}, {@code <img ...>},
 *       {@code <hr>}, {@code <meta charset="UTF-8">});</li>
 *   <li>bare HTML entities like {@code &nbsp;} that are undeclared in
 *       XHTML1.0-strict without a doctype;</li>
 *   <li>attribute values without quotes or with unescaped {@code &}.</li>
 * </ul>
 *
 * <p>Every one of these was showing up in stored {@code canonical_html}
 * because the studio persists whatever docx-preview writes to the
 * canvas, and docx-preview aims for browser fidelity — not strict
 * XHTML. The finalize path SAX-crashed on the first unclosed {@code
 * <p>} it hit, so no PDF ever rendered for that template.</p>
 *
 * <p>The fix: parse with jsoup's lenient HTML5 parser (which auto-
 * closes tags per the HTML tag-closing rules), then serialise with
 * {@link Document.OutputSettings.Syntax#xml}, which forces every void
 * tag to self-close and every open tag to have a matching end tag.
 * Output is DOCTYPE-less so callers can wrap it in whatever XHTML
 * shell they need.</p>
 *
 * <h2>What survives the round trip</h2>
 * <ul>
 *   <li>{@code <span data-field-id="...">} anchors — jsoup preserves
 *       custom attributes verbatim;</li>
 *   <li>{@code <img src="data:image/png;base64,...">} signature images
 *       — data URLs are opaque attribute values, no reparse;</li>
 *   <li>interpolated field text — text nodes pass through unchanged;
 *       jsoup re-escapes the four XML metachars if the caller left them
 *       raw, which is a strict improvement.</li>
 * </ul>
 *
 * <p>Applied at three sites:</p>
 * <ol>
 *   <li>{@link DocumentInstancePdfRenderer} — the last-mile guard, so
 *       finalize tolerates ANY sloppy stored HTML;</li>
 *   <li>{@code EditableTemplateAdminService} — the studio save path, so
 *       newly-authored templates land clean on disk;</li>
 *   <li>{@code SchemaFixupRunner.ensureIdmsCanonicalHtmlXhtmlBackfill()}
 *       — a one-shot idempotent backfill so already-authored
 *       templates are repaired without re-authoring.</li>
 * </ol>
 */
public final class XhtmlNormalizer {

    private XhtmlNormalizer() {}

    /**
     * Parse the given HTML fragment as HTML5 (lenient — auto-closes
     * missing end tags per the tag-closing rules) and re-serialise as
     * XHTML: every void self-closed, every open tag matched, entities
     * XML-safe. Returns just the {@code <body>} inner HTML so callers
     * can wrap it in their own {@code <html>/<head>} shell.
     *
     * <p>{@code null} / blank input → empty string. Never throws for
     * well-formed HTML; if the parser somehow fails, returns the
     * input unchanged so callers keep the pre-fix behaviour rather
     * than crashing.</p>
     */
    public static String toXhtmlFragment(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            Document doc = Jsoup.parse(html);
            doc.outputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml)
                    .prettyPrint(false)
                    .charset("UTF-8");
            // Jsoup wraps the input in <html><head/><body/></html>; the
            // caller only wants the body inner HTML back so it can be
            // dropped into an existing XHTML shell.
            return doc.body().html();
        } catch (Exception e) {
            // Defensive — parser exceptions on well-formed input should
            // be effectively impossible, but if it happens we don't want
            // to make the render path WORSE than it was before.
            return html;
        }
    }
}
