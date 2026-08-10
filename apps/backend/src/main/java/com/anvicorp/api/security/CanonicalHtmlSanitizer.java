package com.anvicorp.api.security;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.regex.Pattern;

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
 * preserve the IDMS invariants:</p>
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
 *   <li>The docx-preview class-based typography system — docx-preview
 *       emits a {@code <style>} block that defines the document's fonts
 *       via CSS classes ({@code .docx_r_1 { font-family: Calibri }}) and
 *       applies those classes to {@code <p>} / {@code <span>} runs. The
 *       structural wrappers ({@code <article class="docx">},
 *       {@code <section>}) scope those class rules. Without preserving
 *       the {@code <style>} block AND the structural wrappers, every
 *       font in every saved template resets to the browser default at
 *       load / render time — for both field values AND non-field text
 *       like paragraph headings. This is the "font changes on save" bug
 *       that motivated the Wave-2 sanitizer having to grow style
 *       support in the first place.</li>
 * </ol>
 *
 * <p>Because {@code <style>} content is a well-known XSS surface (CSS
 * expressions, {@code url(javascript:…)}, {@code -moz-binding},
 * {@code behavior:}, {@code @import url("javascript:…")}), the
 * sanitizer post-scrubs every preserved {@code <style>} element's text
 * body via {@link #scrubCss(String)} — replaces the well-known
 * dangerous constructs with an inert CSS comment. Modern browsers have
 * long since dropped support for most of these vectors, but defense in
 * depth is worth the four lines.</p>
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
     * on {@code img[src]} for base64 signature images, plus the
     * docx-preview structural wrappers and its {@code <style>} block
     * that carries every class-based font declaration.
     */
    private static final Safelist SAFELIST = Safelist.relaxed()
            // Preserve the field-placeholder anchor + class + typography
            // markers. {@code data-df-inherit="1"} is set by
            // {@code applyInheritedTypography} in the studio at wrap time
            // as an idempotency guard — with the marker present, the shared
            // paint pass in {@code InstanceRenderer} SKIPS re-running the
            // typography inheritance at render time (which could otherwise
            // pick a different source than the studio did and stamp a
            // different font on the same field span after the sanitize +
            // render round-trip). Stripping the marker was the residual
            // "field-only font changes on save" bug: the wrap-time font
            // stamp was correct, but paint re-hoisted from the render-time
            // DOM cascade — sometimes the browser default — and overwrote
            // it. Same treatment for future {@code data-df-*} markers so
            // the safelist doesn't have to widen for every helper added.
            .addAttributes("span", "data-field-id", "data-field-name",
                    "data-field-type", "data-df-inherit", "class", "style")
            // Allow class + style on EVERY safelisted tag. Docx-preview
            // encodes fonts in two ways depending on version + document
            // structure:
            //   1. an injected <style> block + class attributes on
            //      elements (preserved by the .addTags("style") below);
            //   2. inline style="font-family:…;font-size:…" attributes
            //      on every kind of run — <span>, <p>, <em>, <strong>,
            //      <h1>..<h6>, <li>, <a>, <blockquote>, <small>, <sub>,
            //      <sup>, <pre>, <code>, <cite>, <q>, …
            // The previous fix added style only to a small subset
            // (span/div/p/td/th/table/tr/img/article/section/…), so
            // fonts encoded inline on bold / italic / heading runs
            // silently got stripped on save. Broadening to :all closes
            // that gap. Inline style values are XSS-scrubbed in the
            // post-clean pass below via scrubStyleAttribute, matching
            // the treatment given to <style> element content.
            .addAttributes(":all", "class", "style")
            .addAttributes("p", "align")
            .addAttributes("td", "colspan", "rowspan", "align", "valign", "width")
            .addAttributes("th", "colspan", "rowspan", "align", "valign", "width")
            .addAttributes("table", "border", "cellpadding", "cellspacing", "width")
            .addAttributes("img", "src", "alt", "width", "height")
            // The signature capture path stores a data: URL — whitelist
            // the data: protocol on img[src] but NOT on any other attribute
            // or tag, so a template can't smuggle a data:text/html payload
            // into an <a href="…">.
            .addProtocols("img", "src", "http", "https", "data")
            // Widen a[href] to keep http/https/mailto/tel intact; jsoup's
            // relaxed defaults already do most of this. javascript: URLs
            // are stripped because they're not on the protocol list.
            .addProtocols("a", "href", "http", "https", "mailto", "tel")
            // Docx-preview structural wrappers + inline stylesheet.
            //  <article class="docx"> — the root wrapper carrying the .docx
            //    class that scopes every rule in the injected stylesheet.
            //  <section> — page containers; wrap runs of paragraphs.
            //  <header> / <footer> — docx headers/footers.
            //  <figure> / <figcaption> — image captions.
            //  <style> — CRITICAL: the class-based typography rules
            //    ({@code .docx_r_1 { font-family: Calibri }}) live here.
            //    Text content is scrubbed via {@link #scrubCss} in
            //    {@link #sanitize} for CSS-based XSS vectors.
            //  <br> / <hr> are already in Safelist.relaxed.
            .addTags("article", "section", "header", "footer",
                    "figure", "figcaption", "style")
            .addAttributes(":all", "id")
            .addAttributes("style", "type", "media");

    private static final Cleaner CLEANER = new Cleaner(SAFELIST);

    /**
     * CSS-based XSS constructs — none of these ever appear in a
     * legitimate docx-preview stylesheet, so a match indicates malicious
     * intent. We neutralise rather than delete so the surrounding
     * rules survive intact.
     * <ul>
     *   <li>{@code expression(…)} — IE-only CSS expressions</li>
     *   <li>{@code -moz-binding: …} — Firefox XBL binding (dead)</li>
     *   <li>{@code behavior: …} — IE-only behavior binding (dead)</li>
     *   <li>{@code url("javascript:…")} / {@code url("vbscript:…")} —
     *       cross-browser historical vectors</li>
     *   <li>{@code @import "javascript:…"} — same via @import</li>
     * </ul>
     */
    private static final Pattern DANGEROUS_CSS = Pattern.compile(
            "(?i)("
                    + "expression\\s*\\("
                    + "|-moz-binding\\s*:"
                    + "|behavior\\s*:"
                    + "|url\\s*\\(\\s*[\"']?\\s*(?:javascript|vbscript|livescript)\\s*:"
                    + "|@import\\s+(?:url\\s*\\(\\s*)?[\"']?\\s*(?:javascript|vbscript):"
                    + ")");

    private CanonicalHtmlSanitizer() {}

    /**
     * Sanitize the canonical HTML fragment. Returns a clean fragment
     * safe to persist and render to other users.
     *
     * <p>Preserves formatting (bold / italic / lists / tables / images),
     * field spans ({@code <span data-field-id="…">…</span>}),
     * base64-embedded signature images, structural wrappers
     * ({@code <article>}, {@code <section>}, {@code <header>},
     * {@code <footer>}, {@code <figure>}) that carry docx-preview class
     * scopes, and the {@code <style>} block that holds every
     * class-based font declaration (with CSS-XSS scrubbing).
     * Strips: {@code <script>}, {@code <iframe>}, {@code <object>},
     * {@code <embed>}, {@code on*} event handlers, {@code javascript:}
     * URLs, and any other tag / attribute / protocol not on the
     * safelist.</p>
     */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) return html;
        try {
            // Parse as a fragment so the sanitizer output doesn't wrap in
            // <html><body>…</body></html> — the caller stores this as an
            // inline body fragment.
            Document dirty = Jsoup.parseBodyFragment(html);
            Document clean = CLEANER.clean(dirty);
            // Post-clean pass A: neutralise CSS-based XSS constructs inside
            // preserved <style> elements. Text content of <style> is a
            // DataNode (raw text) in jsoup; scrub in place. Modern
            // docx-preview output NEVER contains these constructs, so
            // any match indicates a tampered template — replace with an
            // inert CSS comment rather than delete so the surrounding
            // rules keep their line numbers / positional integrity for
            // debugging.
            for (Element styleEl : clean.getElementsByTag("style")) {
                String css = styleEl.data();
                if (css == null || css.isEmpty()) continue;
                String scrubbed = scrubCss(css);
                if (!scrubbed.equals(css)) {
                    log.info("[CanonicalHtmlSanitizer] scrubbed dangerous CSS constructs "
                            + "from preserved <style> block ({} chars → {} chars)",
                            css.length(), scrubbed.length());
                    // Replace the DataNode's whole content in place.
                    styleEl.dataNodes().forEach(node -> node.setWholeData(""));
                    styleEl.appendChild(new DataNode(scrubbed));
                }
            }
            // Post-clean pass B: same scrub for INLINE style attributes
            // on every element. Inline style is now allowed globally so
            // docx-preview's font-family / font-size runs survive save;
            // that opens the same CSS-XSS surface the <style>-content
            // scrub already defends against. url(javascript:…) inside
            // style="background-image:url(javascript:…)" is the classic
            // one. Same regex, same neutralisation.
            for (Element el : clean.getAllElements()) {
                String inline = el.attr("style");
                if (inline.isEmpty()) continue;
                String scrubbedInline = scrubCss(inline);
                if (!scrubbedInline.equals(inline)) {
                    log.info("[CanonicalHtmlSanitizer] scrubbed dangerous CSS from inline "
                            + "style attribute on <{}>", el.tagName());
                    el.attr("style", scrubbedInline);
                }
            }
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

    /** Replace every dangerous CSS token with an inert comment so the
     *  payload is broken while the surrounding declarations still parse. */
    static String scrubCss(String css) {
        if (css == null || css.isEmpty()) return css;
        return DANGEROUS_CSS.matcher(css).replaceAll("/*stripped*/");
    }
}
