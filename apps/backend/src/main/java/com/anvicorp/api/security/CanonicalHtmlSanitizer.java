package com.anvicorp.api.security;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Security Wave 2 (M) — jsoup-based sanitizer for the IDMS canonical HTML.
 *
 * <p>The canonical HTML is derived from an admin-uploaded DOCX and later
 * rendered to interns via {@code dangerouslySetInnerHTML} (and to the PDF
 * pipeline). Without sanitization a template author (or an attacker who
 * compromises the studio) could embed {@code <script>}, {@code onerror=}
 * handlers, or {@code javascript:} URLs that would fire when other users
 * view the document — stored XSS.</p>
 *
 * <p>The sanitizer is called on {@code saveSchema()} AFTER the studio's
 * XhtmlNormalizer has run, so the input is already well-formed XHTML.
 * The safelist is a superset of {@code Safelist.relaxed()} extended to
 * preserve the two IDMS invariants:</p>
 * <ol>
 *   <li>{@code <span data-field-id="…" class="doc-field">} field
 *       placeholders — the {@code data-field-id} anchor + the
 *       {@code doc-field} class must survive so the fill workflow can
 *       still locate each field on the rendered document.</li>
 *   <li>Signature {@code <img src="data:image/png;base64,…">} tags —
 *       {@code data:} URLs are normally stripped by jsoup; the safelist
 *       whitelists the {@code data:} protocol on {@code img[src]} only,
 *       and the interpolator ({@code DocumentInstancePdfRenderer}) already
 *       HTML-escapes the base64 payload.</li>
 * </ol>
 *
 * <p>Field VALUE interpolation was already safe (values are
 * HTML-escaped before splicing into the canonical HTML at render time —
 * confirmed by the pre-Wave-2 audit). This sanitizer only closes the
 * source-side gap where a malicious template author could seed
 * pre-escaped script tags directly into {@code canonicalHtml}.</p>
 */
@Slf4j
public final class CanonicalHtmlSanitizer {

    /**
     * Safelist tailored to the IDMS canonical HTML. Starts from
     * {@code Safelist.relaxed()} — which permits the full formatting set
     * (p / span / strong / em / u / h1-h6 / ul / ol / li / table / thead
     * / tbody / tr / th / td / br / hr / a / img / blockquote / q /
     * sub / sup / small / big / pre / code / caption / colgroup / col) —
     * plus the IDMS-specific attributes and the {@code data:} protocol
     * on {@code img[src]} for base64 signature images.
     */
    private static final Safelist SAFELIST = Safelist.relaxed()
            // Preserve the field-placeholder anchor + class.
            .addAttributes("span", "data-field-id", "data-field-name",
                    "data-field-type", "class", "style")
            .addAttributes(":all", "class")
            .addAttributes("div", "class", "style")
            .addAttributes("p", "class", "style", "align")
            .addAttributes("td", "class", "style", "colspan", "rowspan", "align", "valign", "width")
            .addAttributes("th", "class", "style", "colspan", "rowspan", "align", "valign", "width")
            .addAttributes("table", "class", "style", "border", "cellpadding", "cellspacing", "width")
            .addAttributes("tr", "class", "style")
            .addAttributes("img", "src", "alt", "width", "height", "class", "style")
            // The signature capture path stores a data: URL — whitelist
            // the data: protocol on img[src] but NOT on any other attribute
            // or tag, so a template can't smuggle a data:text/html payload
            // into an <a href="…">.
            .addProtocols("img", "src", "http", "https", "data")
            // Widen a[href] to keep http/https/mailto/tel intact; jsoup's
            // relaxed defaults already do most of this. javascript: URLs
            // are stripped because they're not on the protocol list.
            .addProtocols("a", "href", "http", "https", "mailto", "tel");

    private static final Cleaner CLEANER = new Cleaner(SAFELIST);

    private CanonicalHtmlSanitizer() {}

    /**
     * Sanitize the canonical HTML fragment. Returns a clean fragment
     * safe to persist and render to other users.
     *
     * <p>Preserves formatting (bold / italic / lists / tables / images),
     * field spans ({@code <span data-field-id="…">…</span>}), and
     * base64-embedded signature images. Strips: {@code <script>},
     * {@code <iframe>}, {@code <object>}, {@code <embed>}, {@code on*}
     * event handlers, {@code javascript:} URLs, and any other tag /
     * attribute / protocol not on the safelist.</p>
     */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) return html;
        try {
            // Parse as a fragment so the sanitizer output doesn't wrap in
            // <html><body>…</body></html> — the caller stores this as an
            // inline body fragment.
            Document dirty = Jsoup.parseBodyFragment(html);
            Document clean = CLEANER.clean(dirty);
            return clean.body().html();
        } catch (Exception ex) {
            // Never let sanitization crash the save. A parse blowup means
            // the input was pathological; return an empty string so the
            // storefront doesn't render whatever the studio sent. The
            // caller layer maps empty canonicalHtml to a validation error
            // via the same path as a client that sent null.
            log.warn("[CanonicalHtmlSanitizer] parse/clean failed — returning empty "
                    + "fragment as fail-safe: {}", ex.getMessage());
            return "";
        }
    }
}
