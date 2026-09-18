package com.anvicorp.api.admin.editabletemplates;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wave B — IDMS metadata layer Stage 2. Uses the Stage-1
 * {@link FormattingProfile} extracted from the source DOCX at
 * upload time to CORRECT the docx-preview canonical HTML at
 * studio-save time, BEFORE the sanitizer runs.
 *
 * <p><b>Why here.</b> docx-preview's DOCX→HTML conversion is lossy —
 * it drops (or mangles) the source document's real formatting in
 * ways that cause every font / bullet / margin / header bug we've
 * hit: header paragraphs lose their negative left-indent (a header
 * with {@code w:ind w:left="-1587"} hanging into the left margin
 * renders at 0), page margins don't match the source's sectPr,
 * bullet list {@code <p>}s don't carry the run's font. The source
 * DOCX XML has the authoritative values — Stage 1 extracted them
 * into the profile — this pass folds them back into the HTML so
 * downstream renders read corrected data.</p>
 *
 * <h2>Where it plugs in</h2>
 * {@code EditableTemplateAdminService.saveSchema} pipes the canvas
 * HTML through {@code XhtmlNormalizer.toXhtmlFragment} (well-formed
 * XHTML), then THIS corrector (data-driven patch), then
 * {@code CanonicalHtmlSanitizer.sanitize} (XSS scrub), then
 * persists. The corrected HTML flows through the existing render
 * pipeline unchanged — {@code DocumentInstancePdfRenderer} does
 * NOT need to know the profile exists.
 *
 * <h2>Fail-open at every layer</h2>
 * <ul>
 *   <li>Null / blank profile JSON → return input unchanged. Legacy
 *       templates (pre-Stage-1) have {@code null} profiles and MUST
 *       render exactly as before — zero regression.</li>
 *   <li>Malformed JSON → warn + return input unchanged.</li>
 *   <li>Any consumer throws → warn + return input unchanged. The
 *       corrector is strictly additive; a broken correction MUST NOT
 *       fail the save.</li>
 * </ul>
 *
 * <h2>Consumers implemented in Stage 2</h2>
 * <ol>
 *   <li>{@link #correctHeader} — the header paragraph's real
 *       alignment + left indent (INCLUDING negative values) applied
 *       as inline {@code text-align} + {@code margin-left} on the
 *       {@code <header>} element. Generic — a left / center / right
 *       / negative-indent header all render at their real
 *       positions. Replaces the rejected hardcoded "force left"
 *       band-aid.</li>
 *   <li>{@link #correctPageGeometry} — the source's real page size
 *       + margins from {@code sectPr} written back onto the first
 *       {@code section.docx}'s inline {@code width}/{@code
 *       min-height}/{@code padding-*}. The renderer's existing
 *       geometry scrape then reads authoritative values instead of
 *       docx-preview's inferred ones. Renderer untouched.</li>
 *   <li>{@link #correctListItemFont} — for {@code <p>} elements
 *       that carry a docx-preview list class (matching
 *       {@code /docx-num-\\d+/}) and have no inline font-family,
 *       inject the list's OWN font-family (Stage 3), falling back
 *       to the profile's body default, so bullet / numbered items
 *       render in the correct font instead of cascading to the
 *       browser default.</li>
 * </ol>
 *
 * <h2>Consumer added in Stage 3</h2>
 * <ol start="4">
 *   <li>{@link #correctListIndent} — the numbering level's real
 *       {@code w:ind} written back as the CSS hanging-indent pair
 *       {@code margin-left} + {@code text-indent}. This is the fix for
 *       the primary visual defect: docx-preview drops the list indent,
 *       so bullets render flush against the left margin and wrapped
 *       lines run back underneath the bullet instead of aligning under
 *       the text. {@code profile.lists()} has been extracted at upload
 *       since Stage 1 but was read by NO consumer until this one — the
 *       authoritative values were already on the row, merely unused.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CanonicalHtmlProfileCorrector {

    /**
     * Marker the studio's formatting toolbar stamps on any element whose
     * layout an admin has DELIBERATELY overridden.
     *
     * <p>This corrector re-asserts the source DOCX's profile on EVERY
     * save, which is right for repairing what docx-preview inferred
     * badly at import — but it cannot otherwise tell an inferred value
     * from an admin's considered override, so it silently reverted
     * every page-margin and list-indent edit the toolbar made. The
     * marker is that missing signal: a marked element keeps the
     * properties the toolbar controls, and import-repair continues
     * everywhere the admin has not overridden.</p>
     *
     * <p>The skip is deliberately PROPERTY-SCOPED, not whole-element:
     * a marked section still gets its authoritative {@code width} /
     * {@code min-height} re-asserted (the toolbar never writes those),
     * only its {@code padding-*} is left alone. The corrector keeps
     * doing every job the toolbar doesn't do.</p>
     *
     * <p>Unlike the studio's visual {@code studio-fmt-active} outline —
     * stripped before save — this attribute is PERSISTED, because the
     * corrector must still see it on the NEXT save. It therefore has to
     * survive {@code CanonicalHtmlSanitizer}, which safelists it
     * alongside the {@code data-field-id} family.</p>
     *
     * <p>Re-importing a source DOCX clears the canonical HTML entirely,
     * so the fresh render carries no markers and the profile is applied
     * in full — the admin re-applies any overrides against the new
     * document, which is the correct behaviour.</p>
     */
    static final String ADMIN_FORMAT_ATTR = "data-fmt-admin";

    /** True when the admin deliberately set this element's layout via
     *  the studio formatting toolbar. */
    private static boolean isAdminFormatted(Element el) {
        return el != null && el.hasAttr(ADMIN_FORMAT_ATTR);
    }

    private final ObjectMapper objectMapper;

    /**
     * Correct the canonical HTML using the source DOCX's formatting
     * profile. Fail-open — returns {@code canonicalHtml} unchanged
     * on any of: null/blank input, null/blank profileJson, malformed
     * profile JSON, jsoup parse blow-up, corrector throw.
     *
     * @param canonicalHtml the studio's serialised (already
     *                      XhtmlNormalizer-passed) HTML fragment
     * @param profileJson   the source DOCX's formatting profile JSON
     *                      as extracted by {@link DocxFormattingExtractor}
     *                      and stored on {@link EditableTemplate}. Null /
     *                      blank for legacy templates.
     * @return corrected HTML, or {@code canonicalHtml} verbatim on
     *         any failure path.
     */
    public String correct(String canonicalHtml, String profileJson) {
        if (canonicalHtml == null || canonicalHtml.isEmpty()) return canonicalHtml;
        if (profileJson == null || profileJson.isBlank()) return canonicalHtml;
        FormattingProfile profile;
        try {
            profile = objectMapper.readValue(profileJson, FormattingProfile.class);
        } catch (JsonProcessingException e) {
            log.warn("[CanonicalHtmlProfileCorrector] profile JSON parse failed "
                    + "(returning input unchanged): {}", e.getMessage());
            return canonicalHtml;
        }
        if (profile == null) return canonicalHtml;
        try {
            Document doc = Jsoup.parseBodyFragment(canonicalHtml);
            doc.outputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml)
                    .prettyPrint(false)
                    .charset("UTF-8");
            correctHeader(doc, profile.header());
            correctFooter(doc, profile.footer());
            correctPageGeometry(doc, profile.page());
            correctListIndent(doc, profile.lists());
            correctListItemFont(doc, profile.bodyDefault(), profile.lists());
            return doc.body().html();
        } catch (Exception e) {
            log.warn("[CanonicalHtmlProfileCorrector] correction pass failed "
                    + "(returning input unchanged): {}", e.getMessage());
            return canonicalHtml;
        }
    }

    // ── Consumer 1: header alignment + indent ────────────────────────

    /**
     * Apply the header's real alignment + left indent from the profile
     * to the {@code <header>} element's inline style.
     *
     * <p>Generic by construction: the profile's alignment is one of
     * {@code left | center | right | both | distribute}, applied
     * verbatim as {@code text-align} on the header. The profile's
     * left indent is applied as {@code margin-left} in the same unit
     * (inches — negative preserved). A document with a centered
     * corporate letterhead gets {@code text-align: center}; a
     * document with a right-aligned page-number header gets
     * {@code text-align: right}; the ANVI offer letter with
     * {@code w:ind w:left="-1587"} gets {@code margin-left:
     * -0.1102in} which pulls the logo to the page edge. Zero
     * hardcoding — everything driven by the profile.</p>
     *
     * <p>Fail-open per-property: any specific property that's missing
     * from the profile (e.g. an alignment was never set) is simply
     * not applied — we don't clobber whatever the docx-preview HTML
     * already had.</p>
     */
    void correctHeader(Document doc, FormattingProfile.HeaderFooterProfile header) {
        if (header == null || header.paragraphs() == null
                || header.paragraphs().isEmpty()) {
            return;
        }
        Elements headers = doc.select("header");
        if (headers.isEmpty()) return;
        Element firstHeader = headers.first();
        // Use the FIRST paragraph's properties as the header-level
        // signal. Multi-paragraph headers exist (rare — page numbers +
        // date etc.) but they overwhelmingly share alignment/indent;
        // Stage 3 can widen to per-paragraph if a consumer emerges.
        FormattingProfile.ParagraphProfile p = header.paragraphs().get(0);
        applyParagraphStyleToElement(firstHeader, p);
    }

    /** Same treatment for the {@code <footer>} element — real
     *  alignment + indent applied from the profile so page-number /
     *  address footers render at their source positions. */
    void correctFooter(Document doc, FormattingProfile.HeaderFooterProfile footer) {
        if (footer == null || footer.paragraphs() == null
                || footer.paragraphs().isEmpty()) {
            return;
        }
        Elements footers = doc.select("footer");
        if (footers.isEmpty()) return;
        Element firstFooter = footers.first();
        FormattingProfile.ParagraphProfile p = footer.paragraphs().get(0);
        applyParagraphStyleToElement(firstFooter, p);
    }

    /** Apply a {@link FormattingProfile.ParagraphProfile}'s
     *  alignment + left indent to a target element (typically
     *  {@code <header>} or {@code <footer>}) as inline CSS. Existing
     *  style declarations are PRESERVED unless the same property is
     *  set by the profile — in which case the profile wins because
     *  it's the authoritative source. */
    private void applyParagraphStyleToElement(
            Element target, FormattingProfile.ParagraphProfile p) {
        // Both properties this writes — text-align and margin-left —
        // are toolbar-controlled, so a deliberate admin override owns
        // the element outright.
        if (isAdminFormatted(target)) return;
        String existing = target.attr("style");
        String textAlign = p.alignment();
        // DOCX's "both" == CSS "justify"; leave other values (left/
        // center/right/distribute) unchanged. "distribute" isn't valid
        // CSS text-align — skip it to avoid emitting invalid CSS.
        String cssTextAlign;
        if (textAlign == null) cssTextAlign = null;
        else switch (textAlign.toLowerCase(Locale.ROOT)) {
            case "left", "center", "right" -> cssTextAlign = textAlign.toLowerCase(Locale.ROOT);
            case "both" -> cssTextAlign = "justify";
            default -> cssTextAlign = null;
        }

        // Left indent — use inches (2 decimals is enough for CSS
        // precision on a print stylesheet; -0.11in is the ANVI case).
        String cssMarginLeft = null;
        if (p.indent() != null && p.indent().left() != null) {
            double inches = p.indent().left().inches();
            cssMarginLeft = formatInches(inches);
        }

        StringBuilder next = new StringBuilder();
        // Keep existing declarations except the two we're overriding.
        String cleaned = stripInlineProperty(
                stripInlineProperty(existing, "text-align"),
                "margin-left");
        if (!cleaned.isBlank()) {
            next.append(cleaned.trim());
            if (!next.toString().endsWith(";")) next.append(";");
        }
        if (cssTextAlign != null) {
            next.append("text-align:").append(cssTextAlign).append(";");
        }
        if (cssMarginLeft != null) {
            next.append("margin-left:").append(cssMarginLeft).append(";");
        }
        if (next.length() == 0) {
            target.removeAttr("style");
        } else {
            target.attr("style", next.toString());
        }
    }

    // ── Consumer 2: authoritative page geometry ──────────────────────

    /**
     * Write the profile's real page size + margins onto the first
     * {@code section.docx}'s inline {@code width} / {@code min-height}
     * / {@code padding-*} so the PDF renderer's existing
     * {@code preparePageGeometry} scrape reads the SOURCE's
     * authoritative values instead of whatever docx-preview happened
     * to infer.
     *
     * <p><b>Design choice.</b> The user's requirement is that render
     * code stays untouched. Two options were available: (a) patch the
     * section.docx style at save-time so the renderer's untouched
     * scrape reads corrected values, (b) pass the profile through to
     * the renderer + branch. Option (a) is strictly additive — the
     * renderer's contract is unchanged — so we take that path. The
     * fallback for legacy null-profile templates is the current
     * scrape-from-HTML behavior, which stays intact.</p>
     */
    void correctPageGeometry(Document doc, FormattingProfile.PageGeometry page) {
        if (page == null) return;
        Elements sections = doc.select("section.docx");
        if (sections.isEmpty()) return;
        // First section only — Stage 1 profile is single-section
        // (matches the current single-section scope in preparePageGeometry).
        Element first = sections.first();
        String existing = first.attr("style");
        StringBuilder next = new StringBuilder();

        // Property-scoped skip: when the admin has set page margins via
        // the studio toolbar, this section's padding-* IS the intended
        // page geometry and preparePageGeometry must scrape it rather
        // than the imported profile's. width / min-height are NOT
        // toolbar-controlled, so they are still re-asserted below —
        // the corrector only steps back from what the admin owns.
        boolean adminMargins = isAdminFormatted(first);

        // Preserve existing declarations we're not overriding.
        String cleaned = adminMargins
                ? stripInlineProperty(existing, "width", "min-height")
                : stripInlineProperty(existing,
                        "padding-top", "padding-right", "padding-bottom", "padding-left",
                        "width", "min-height");
        if (!cleaned.isBlank()) {
            next.append(cleaned.trim());
            if (!next.toString().endsWith(";")) next.append(";");
        }
        if (page.widthIn() != null) {
            next.append("width:").append(formatInches(page.widthIn().inches())).append(";");
        }
        if (page.heightIn() != null) {
            next.append("min-height:").append(formatInches(page.heightIn().inches())).append(";");
        }
        if (page.margins() != null && !adminMargins) {
            FormattingProfile.Margins m = page.margins();
            if (m.top() != null) {
                next.append("padding-top:").append(formatInches(m.top().inches())).append(";");
            }
            if (m.right() != null) {
                next.append("padding-right:").append(formatInches(m.right().inches())).append(";");
            }
            if (m.bottom() != null) {
                next.append("padding-bottom:").append(formatInches(m.bottom().inches())).append(";");
            }
            if (m.left() != null) {
                next.append("padding-left:").append(formatInches(m.left().inches())).append(";");
            }
        }
        if (next.length() == 0) first.removeAttr("style");
        else first.attr("style", next.toString());
    }

    // ── Consumer 3: list-item font ───────────────────────────────────

    /**
     * For every list item that doesn't already carry an inline
     * {@code font-family}, inject the font the list's own runs use —
     * falling back to the profile's body default when the list didn't
     * name one. Fixes the "list items lose their font on save" bug
     * docx-preview causes by not putting the run's font on the
     * enclosing {@code <p>}.
     *
     * <p><b>Stage 3 — why the list's own font, not the body default.</b>
     * {@code BodyDefault} is probed by taking the first font named by
     * ANY run in document order. On a letterhead document (every ANVI
     * offer letter) that run is in the address or date line, whose font
     * the bullets never use — so the body default injected here was
     * actively WRONG for list items. {@link
     * FormattingProfile.ListDefinition#fontFamily()} is tallied from the
     * runs of the paragraphs that actually carry the numId, which is the
     * only authoritative source for what the bullets should look like.
     * The body default stays as the fallback so a list whose runs
     * inherit their font (naming none explicitly) still gets something
     * sane, and so version-1 profiles — which have no per-list font —
     * behave exactly as before.</p>
     *
     * <p>Conservative — we only ADD, never override an explicit
     * declaration docx-preview happened to emit.</p>
     */
    void correctListItemFont(Document doc, FormattingProfile.BodyDefault bodyDefault,
            Map<String, FormattingProfile.ListDefinition> lists) {
        String fallbackFont = null;
        Double fallbackSizePt = null;
        if (bodyDefault != null) {
            if (bodyDefault.fontFamily() != null && !bodyDefault.fontFamily().isBlank()) {
                fallbackFont = bodyDefault.fontFamily().trim();
            }
            fallbackSizePt = bodyDefault.fontSizePt();
        }
        for (Element item : listItems(doc)) {
            FormattingProfile.ListDefinition def = resolveList(item, lists);

            String font = (def != null && def.fontFamily() != null
                    && !def.fontFamily().isBlank())
                    ? def.fontFamily().trim() : fallbackFont;
            Double sizePt = (def != null && def.fontSizePt() != null)
                    ? def.fontSizePt() : fallbackSizePt;

            String existing = item.attr("style");
            String lower = existing.toLowerCase(Locale.ROOT);
            boolean needsFont = font != null && !lower.contains("font-family");
            boolean needsSize = sizePt != null && !lower.contains("font-size");
            if (!needsFont && !needsSize) continue;

            StringBuilder next = new StringBuilder();
            if (!existing.isBlank()) {
                next.append(existing.trim());
                if (!next.toString().endsWith(";")) next.append(";");
            }
            if (needsFont) {
                next.append("font-family:").append(font).append(";");
            }
            if (needsSize) {
                next.append("font-size:")
                        .append(String.format(Locale.ROOT, "%.1fpt", sizePt))
                        .append(";");
            }
            item.attr("style", next.toString());
        }
    }

    // ── Consumer 4: list hanging indent (Stage 3) ────────────────────

    /**
     * Apply each list level's real {@code w:ind} to its items as the CSS
     * hanging-indent pair.
     *
     * <p><b>The defect this fixes.</b> docx-preview drops the numbering
     * level's indent entirely, so every bullet renders hard against the
     * left margin and — worse — a wrapped line returns to the margin
     * too, sliding underneath its own bullet instead of aligning with
     * the text above it. The offer letter's duty list is the visible
     * symptom: "AI/ML applications." wraps back under the bullet rather
     * than lining up under "Collect, clean,".</p>
     *
     * <p><b>The mapping.</b> Word's model is an indent for the whole
     * paragraph plus a first-line delta; CSS spells the same thing
     * {@code margin-left} + {@code text-indent}. Stage 1 already
     * normalises {@code w:hanging} into a NEGATIVE {@code
     * indentFirstLine} (see {@code DocxFormattingExtractor.extractLists})
     * so the two profile values drop straight into the two CSS
     * properties with no sign juggling here. The ANVI list
     * ({@code left=1080 hanging=360}) becomes
     * {@code margin-left:0.75in; text-indent:-0.25in} — marker at
     * 0.50in, wrapped text at 0.75in, which is exactly the source
     * document's geometry.</p>
     *
     * <p><b>Authoritative, unlike the font pass.</b> Where the font
     * consumer only fills a gap, this one OVERWRITES any existing
     * horizontal indent: whatever docx-preview inferred is precisely
     * the value we know to be wrong, and the profile carries the
     * source's own number. Nothing is hardcoded — a template with a
     * different indent gets its own, and a level with no {@code w:ind}
     * at all is left completely alone.</p>
     */
    void correctListIndent(Document doc,
            Map<String, FormattingProfile.ListDefinition> lists) {
        if (lists == null || lists.isEmpty()) return;
        for (Element item : listItems(doc)) {
            // Every property this rewrites — margin-left, text-indent and
            // the padding-left that expresses the same offset — is
            // toolbar-controlled, so a marked list paragraph keeps the
            // admin's indent instead of being reset to the source level's.
            if (isAdminFormatted(item)) continue;
            FormattingProfile.LevelDefinition level = resolveLevel(item, lists);
            if (level == null) continue;
            FormattingProfile.Length left = level.indentLeft();
            FormattingProfile.Length firstLine = level.indentFirstLine();
            // Level carries no indent information — leave the element
            // exactly as docx-preview emitted it.
            if (left == null && firstLine == null) continue;

            // padding-left goes too: it is the other way a converter can
            // express the same horizontal offset, and leaving it would
            // stack on top of the margin we are about to set.
            String cleaned = stripInlineProperty(item.attr("style"),
                    "margin-left", "text-indent", "padding-left");
            StringBuilder next = new StringBuilder();
            if (!cleaned.isBlank()) {
                next.append(cleaned.trim());
                if (!next.toString().endsWith(";")) next.append(";");
            }
            if (left != null) {
                next.append("margin-left:")
                        .append(formatInches(left.inches())).append(";");
            }
            if (firstLine != null) {
                next.append("text-indent:")
                        .append(formatInches(firstLine.inches())).append(";");
            }
            item.attr("style", next.toString());
        }
    }

    // ── List helpers ─────────────────────────────────────────────────

    /** docx-preview names a list paragraph
     *  {@code docx-num-<numId>-<ilvl>} (e.g. {@code docx-num-40-0}).
     *  Digit runs are length-capped so a malformed class can't overflow
     *  {@link Integer#parseInt}. */
    private static final Pattern NUM_CLASS =
            Pattern.compile("docx-num-(\\d{1,9})-(\\d{1,9})");

    /** Every element docx-preview marked as a list item. {@code <p>} is
     *  what it emits today; {@code <li>} is matched too so a converter
     *  change (or a hand-authored template) doesn't silently lose the
     *  correction. */
    private static Elements listItems(Document doc) {
        return doc.select("p[class*=\"docx-num-\"], li[class*=\"docx-num-\"]");
    }

    /** Resolve the profile's list definition for an item from its
     *  docx-preview class, or null when the class doesn't parse or the
     *  profile has no entry for that numId. */
    private static FormattingProfile.ListDefinition resolveList(
            Element item, Map<String, FormattingProfile.ListDefinition> lists) {
        if (lists == null || lists.isEmpty()) return null;
        Matcher m = NUM_CLASS.matcher(item.className());
        if (!m.find()) return null;
        return lists.get(m.group(1));
    }

    /** Resolve the specific numbering LEVEL for an item — the numId
     *  gives the definition, the ilvl selects the level within it.
     *  Null when either lookup misses. */
    private static FormattingProfile.LevelDefinition resolveLevel(
            Element item, Map<String, FormattingProfile.ListDefinition> lists) {
        if (lists == null || lists.isEmpty()) return null;
        Matcher m = NUM_CLASS.matcher(item.className());
        if (!m.find()) return null;
        FormattingProfile.ListDefinition def = lists.get(m.group(1));
        if (def == null || def.levels() == null) return null;
        int ilvl = Integer.parseInt(m.group(2));
        for (FormattingProfile.LevelDefinition level : def.levels()) {
            if (level != null && level.levelIndex() == ilvl) return level;
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Format an inches value to a CSS length like {@code -0.11in} or
     *  {@code 8.5in}. Two decimals is enough for print — CSS ignores
     *  precision below the pixel and even openhtmltopdf's PDF engine
     *  won't distinguish smaller deltas. Negative values are
     *  preserved verbatim (the whole point). */
    private static String formatInches(double inches) {
        return String.format(Locale.ROOT, "%.2fin", inches);
    }

    /**
     * Remove one or more properties from an inline style string,
     * returning the remainder. Preserves declaration order for
     * everything not removed. Handles missing / null input and
     * whitespace variations without throwing.
     */
    private static String stripInlineProperty(String style, String... propsToRemove) {
        if (style == null || style.isBlank() || propsToRemove == null
                || propsToRemove.length == 0) {
            return style == null ? "" : style;
        }
        String[] parts = style.split(";");
        StringBuilder kept = new StringBuilder();
        outer:
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                kept.append(trimmed).append(";");
                continue;
            }
            String prop = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            for (String remove : propsToRemove) {
                if (prop.equals(remove.toLowerCase(Locale.ROOT))) continue outer;
            }
            kept.append(trimmed).append(";");
        }
        return kept.toString();
    }

    /**
     * Escape hatch for tests that want to observe intermediate state
     * without triggering the fail-open catch-all. Not used at runtime.
     */
    @SuppressWarnings("unused")
    static List<String> notForRuntime() {
        return List.of();
    }
}
