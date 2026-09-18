/**
 * Formatting-toolbar primitives for the Editable Documents studio.
 *
 * <p>The studio's admin can repair a template's LAYOUT — line spacing,
 * paragraph spacing, alignment, indentation, font size, page margins —
 * by selecting an element on the docx-preview canvas and clicking a
 * control. Each control writes exactly ONE inline CSS declaration onto
 * the resolved element; the mutated DOM is serialised by the existing
 * {@code canvas.innerHTML} save path, survives
 * {@code CanonicalHtmlSanitizer} (which allows {@code style} on
 * {@code :all}), and is honoured verbatim by
 * {@code DocumentInstancePdfRenderer} at PDF time.</p>
 *
 * <p>FORMATTING ONLY. Nothing here edits text, toggles
 * {@code contentEditable}, or changes wording — the canvas stays a
 * read-only-text surface and the field-anchor wrap flow is untouched.</p>
 *
 * <h3>Why the style attribute is managed as a STRING</h3>
 *
 * <p>The obvious implementation is {@code el.style.setProperty(prop,
 * value)}. That is wrong here. CSSOM serialisation COLLAPSES a complete
 * set of longhands into its shorthand — set all four
 * {@code padding-top/right/bottom/left} on a section and the browser
 * writes back {@code style="padding: 1in 0.88in 1in 1in"}. The PDF
 * renderer's {@code preparePageGeometry} scrapes page margins by
 * regex-matching the LONGHAND names; a collapsed shorthand makes all
 * four lookups miss and the renderer silently falls back to its default
 * A4 margins. The admin's edit would vanish from the executed PDF.</p>
 *
 * <p>So every write goes through {@link setInlineDeclarations}, which
 * parses the existing attribute into ordered pairs, patches the named
 * properties in place, and re-serialises as explicit longhands. No
 * shorthand collapse, and existing docx-preview declarations keep their
 * original order and values.</p>
 */

/** Marker class for the currently-targeted element. PURELY VISUAL —
 *  stripped before save by {@link clearFormatHighlight} (called from
 *  the studio's {@code resetPreviewClasses}) so it can never reach the
 *  persisted canonical HTML. */
export const FMT_ACTIVE_CLASS = 'studio-fmt-active';

/** Variant marker for a whole page section — renders a corner "Page"
 *  badge instead of wrapping the entire page in an outline. Same
 *  strip-before-save contract as {@link FMT_ACTIVE_CLASS}. */
export const FMT_ACTIVE_SECTION_CLASS = 'studio-fmt-active--section';

/** Paragraph-level declarations this toolbar owns. Reset clears exactly
 *  this set — never a property the toolbar didn't write (font-family,
 *  color, display:list-item, … all survive a reset untouched). */
export const PARAGRAPH_PROPS = [
  'line-height',
  'margin-top',
  'margin-bottom',
  'text-align',
  'margin-left',
  'text-indent',
] as const;

/** Run-level declarations this toolbar owns. */
export const RUN_PROPS = ['font-size'] as const;

/** Section-level declarations this toolbar owns — the four page-margin
 *  longhands scraped by {@code preparePageGeometry}. */
export const SECTION_PROPS = [
  'padding-top',
  'padding-right',
  'padding-bottom',
  'padding-left',
] as const;

/**
 * Mirrors {@code DocumentInstancePdfRenderer.SIMPLE_LENGTH} EXACTLY:
 * {@code ^-?\d+(?:\.\d+)?(?:in|cm|mm|pt|px)$}.
 *
 * <p>A page-margin value that fails this test is not merely ugly — the
 * renderer's {@code extractInlineLength} returns null for it, which
 * makes {@code preparePageGeometry} discard ALL FOUR margins and fall
 * back to defaults. The margins control refuses to write a set that
 * wouldn't round-trip, so the toolbar never shows the admin a value the
 * PDF would ignore.</p>
 */
export const SIMPLE_LENGTH_RE = /^-?\d+(?:\.\d+)?(?:in|cm|mm|pt|px)$/;

export type LengthUnit = 'in' | 'cm' | 'mm' | 'pt' | 'px';

export type FormatTarget = {
  /** Nearest ancestor {@code <p>} — the target for line-spacing,
   *  paragraph spacing, alignment and indentation. */
  paragraph: HTMLElement | null;
  /** Nearest ancestor docx run span (or the paragraph as a fallback) —
   *  the target for font-size. */
  run: HTMLElement | null;
  /** Nearest ancestor {@code <section class="docx">} — the page the
   *  admin clicked into. NOTE: the margins control does NOT write here;
   *  see {@link firstPageSection}. */
  section: HTMLElement | null;
};

export const EMPTY_FORMAT_TARGET: FormatTarget = {
  paragraph: null,
  run: null,
  section: null,
};

/**
 * Resolve a selection anchor / click target to the three elements the
 * toolbar's controls act on.
 *
 * <p>docx-preview's output nests strictly —
 * {@code <section class="docx"> > <article> > <p class="docx_…"> >
 * <span class="docx_r_N">text</span>} — so every target is one
 * {@code closest()} hop from wherever the admin clicked.</p>
 *
 * <p>The run selector uses {@code [class*="docx_r_"]} rather than
 * {@code [class^=…]} so a run that carries a second class
 * ({@code class="highlight docx_r_4"}) still resolves; a prefix match
 * would miss it and silently fall back to paragraph-level font sizing.
 * {@code span.doc-field} is included so an admin can resize a field
 * placeholder's text the same way as ordinary text.</p>
 */
export function resolveFormatTarget(
  node: Node | null | undefined,
  canvas: HTMLElement | null,
): FormatTarget {
  if (!node || !canvas || !canvas.contains(node)) return EMPTY_FORMAT_TARGET;
  const el =
    node.nodeType === Node.ELEMENT_NODE
      ? (node as HTMLElement)
      : (node.parentElement as HTMLElement | null);
  if (!el || !canvas.contains(el)) return EMPTY_FORMAT_TARGET;
  const paragraph = el.closest('p') as HTMLElement | null;
  const run =
    (el.closest(
      'span[class*="docx_r_"], span.doc-field',
    ) as HTMLElement | null) ?? paragraph;
  const section = el.closest('section.docx') as HTMLElement | null;
  return { paragraph, run, section };
}

/**
 * The section whose inline padding actually becomes the PDF's
 * {@code @page margin}.
 *
 * <p>{@code preparePageGeometry} scrapes the FIRST {@code section.docx}
 * and applies the result to every page. Letting the margins control
 * write to whichever section the admin happened to click would produce
 * an edit the PDF ignores on every page but the first — precisely the
 * "control the PDF ignores" failure mode. So the margins control always
 * reads and writes the first section (and mirrors the value onto the
 * rest, see {@link writePageMargins}).</p>
 */
export function firstPageSection(canvas: HTMLElement | null): HTMLElement | null {
  if (!canvas) return null;
  return canvas.querySelector('section.docx') as HTMLElement | null;
}

/** Parse an inline style attribute into ordered [prop, value] pairs. */
function parseStyleAttr(styleAttr: string): Array<[string, string]> {
  const out: Array<[string, string]> = [];
  for (const chunk of styleAttr.split(';')) {
    const idx = chunk.indexOf(':');
    if (idx <= 0) continue;
    const prop = chunk.slice(0, idx).trim().toLowerCase();
    const value = chunk.slice(idx + 1).trim();
    if (prop && value) out.push([prop, value]);
  }
  return out;
}

/** Read ONE inline declaration's value. Returns '' when unset — the
 *  toolbar renders that as "from source" rather than inventing a value. */
export function readInlineDeclaration(
  el: HTMLElement | null,
  prop: string,
): string {
  if (!el) return '';
  const want = prop.trim().toLowerCase();
  for (const [p, v] of parseStyleAttr(el.getAttribute('style') ?? '')) {
    if (p === want) return v;
  }
  return '';
}

/**
 * Set (value) or remove (null / '') one or more inline declarations,
 * re-serialising the whole attribute as explicit longhands.
 *
 * <p>Existing declarations keep their original position so a formatting
 * edit never reshuffles the docx-preview declarations around it — which
 * keeps save diffs small and readable.</p>
 */
export function setInlineDeclarations(
  el: HTMLElement | null,
  patch: Record<string, string | null>,
): void {
  if (!el) return;
  const normalized = new Map<string, string | null>();
  for (const [k, v] of Object.entries(patch)) {
    normalized.set(k.trim().toLowerCase(), v);
  }
  const kept: Array<[string, string]> = [];
  const applied = new Set<string>();
  for (const [prop, value] of parseStyleAttr(el.getAttribute('style') ?? '')) {
    if (normalized.has(prop)) {
      // Collapse any duplicate declaration of a patched property — the
      // last-wins CSS semantics are preserved because we write a single
      // authoritative value.
      if (applied.has(prop)) continue;
      applied.add(prop);
      const next = normalized.get(prop);
      if (next !== null && next !== undefined && next !== '') {
        kept.push([prop, next]);
      }
      continue;
    }
    kept.push([prop, value]);
  }
  // Append patched properties that weren't already present.
  for (const [prop, value] of normalized) {
    if (applied.has(prop)) continue;
    if (value === null || value === '') continue;
    kept.push([prop, value]);
  }
  const out = kept.map(([p, v]) => `${p}: ${v}`).join('; ');
  if (out) el.setAttribute('style', out);
  else el.removeAttribute('style');
}

/** True iff the value round-trips through the renderer's page-margin
 *  scrape. */
export function isSimpleLength(value: string): boolean {
  return SIMPLE_LENGTH_RE.test(value.trim().toLowerCase());
}

/**
 * Format a number + unit as a {@link SIMPLE_LENGTH_RE}-compatible token.
 * Rounds to 3dp so float noise (0.7500000000001) can't produce a value
 * the renderer's regex rejects.
 */
export function formatLength(value: number, unit: LengthUnit): string {
  if (!Number.isFinite(value)) return `0${unit}`;
  const rounded = Math.round(value * 1000) / 1000;
  return `${rounded}${unit}`;
}

/** Parse a CSS length into its number + unit. Returns null when the
 *  value isn't a simple length (calc(), auto, percentages, …). */
export function parseLength(
  value: string,
): { value: number; unit: LengthUnit } | null {
  const v = value.trim().toLowerCase();
  const m = /^(-?\d+(?:\.\d+)?)(in|cm|mm|pt|px)$/.exec(v);
  if (!m) return null;
  return { value: Number(m[1]), unit: m[2] as LengthUnit };
}

/** Best-effort conversion to points for the steppers' display. */
export function toPoints(value: string): number | null {
  const parsed = parseLength(value);
  if (!parsed) return null;
  switch (parsed.unit) {
    case 'pt': return parsed.value;
    case 'in': return parsed.value * 72;
    case 'cm': return parsed.value * 28.3465;
    case 'mm': return parsed.value * 2.83465;
    case 'px': return parsed.value * 0.75;
    default: return null;
  }
}

/** Best-effort conversion to inches for the indent / margin steppers. */
export function toInches(value: string): number | null {
  const pt = toPoints(value);
  return pt === null ? null : pt / 72;
}

/** Computed value of a property, for showing the inherited baseline
 *  when nothing is set inline. Returns '' outside the browser. */
export function readComputed(el: HTMLElement | null, prop: string): string {
  if (!el || typeof window === 'undefined') return '';
  try {
    return window.getComputedStyle(el).getPropertyValue(prop).trim();
  } catch {
    return '';
  }
}

/**
 * Effective line-height as a unitless ratio, for the line-spacing
 * control's "currently showing" hint. getComputedStyle reports px, so
 * divide by the computed font-size to recover the ratio the admin
 * thinks in (1.0 / 1.15 / 1.5 / 2.0).
 */
export function computedLineRatio(el: HTMLElement | null): number | null {
  const lh = readComputed(el, 'line-height');
  const fs = readComputed(el, 'font-size');
  const lhPx = /^(-?\d+(?:\.\d+)?)px$/.exec(lh);
  const fsPx = /^(-?\d+(?:\.\d+)?)px$/.exec(fs);
  if (!lhPx || !fsPx) return null;
  const size = Number(fsPx[1]);
  if (!size) return null;
  return Math.round((Number(lhPx[1]) / size) * 100) / 100;
}

export type PageMargins = {
  top: string;
  right: string;
  bottom: string;
  left: string;
};

/**
 * Read the page margins the renderer would actually scrape.
 *
 * <p>All-or-nothing, mirroring {@code preparePageGeometry}: if ANY of
 * the four longhands is missing or not a simple length, the renderer
 * discards the whole set and uses its defaults — so this returns null
 * and the control reports "renderer defaults" rather than showing three
 * values that have no effect.</p>
 */
export function readPageMargins(section: HTMLElement | null): PageMargins | null {
  if (!section) return null;
  const top = readInlineDeclaration(section, 'padding-top');
  const right = readInlineDeclaration(section, 'padding-right');
  const bottom = readInlineDeclaration(section, 'padding-bottom');
  const left = readInlineDeclaration(section, 'padding-left');
  const all = [top, right, bottom, left];
  if (!all.every((v) => v && isSimpleLength(v))) return null;
  return { top, right, bottom, left };
}

/**
 * Write all four page-margin longhands.
 *
 * <p>Two invariants, both load-bearing for the executed PDF:</p>
 * <ol>
 *   <li>ALL FOUR values are written together and every one must pass
 *       {@link isSimpleLength} — a partial or unparseable set makes
 *       {@code preparePageGeometry} fall back to defaults, silently
 *       discarding the admin's edit. A rejected write returns false and
 *       changes nothing.</li>
 *   <li>The value is mirrored onto EVERY {@code section.docx}, not just
 *       the first. The renderer scrapes the first section for
 *       {@code @page margin} and then strips padding off all of them,
 *       so the PDF is uniform either way — but the studio canvas paints
 *       each section's padding literally, so mirroring keeps what the
 *       admin sees on the canvas consistent with what the PDF
 *       produces.</li>
 * </ol>
 */
export function writePageMargins(
  canvas: HTMLElement | null,
  margins: PageMargins,
): boolean {
  if (!canvas) return false;
  const { top, right, bottom, left } = margins;
  if (![top, right, bottom, left].every(isSimpleLength)) return false;
  const sections = canvas.querySelectorAll<HTMLElement>('section.docx');
  if (sections.length === 0) return false;
  sections.forEach((section) => {
    setInlineDeclarations(section, {
      'padding-top': top,
      'padding-right': right,
      'padding-bottom': bottom,
      'padding-left': left,
    });
  });
  return true;
}

/** Drop the four page-margin longhands from every section, returning the
 *  document to the renderer's default geometry. */
export function clearPageMargins(canvas: HTMLElement | null): void {
  if (!canvas) return;
  canvas.querySelectorAll<HTMLElement>('section.docx').forEach((section) => {
    setInlineDeclarations(section, {
      'padding-top': null,
      'padding-right': null,
      'padding-bottom': null,
      'padding-left': null,
    });
  });
}

/** Descendants of a paragraph that can carry their own inline
 *  font-size and would therefore mask a paragraph-level declaration. */
const RUN_DESCENDANT_SELECTOR = 'span, em, strong, b, i, u, a, small, sub, sup, code';

/**
 * Apply a font-size to a whole paragraph.
 *
 * <p>docx-preview stamps an inline {@code font-size} on each run, so a
 * declaration on the {@code <p>} alone would sit underneath them and
 * change nothing visible. Stamping the same value on every descendant
 * run makes the edit actually take — and keeps {@link clearFormatting}
 * symmetric, since it clears exactly the same set. Clearing restores
 * the source size because docx-preview ALSO emits the original size as
 * a class rule in its {@code <style>} block, which the sanitizer
 * preserves.</p>
 */
export function setParagraphFontSize(
  paragraph: HTMLElement | null,
  value: string | null,
): void {
  if (!paragraph) return;
  setInlineDeclarations(paragraph, { 'font-size': value });
  paragraph
    .querySelectorAll<HTMLElement>(RUN_DESCENDANT_SELECTOR)
    .forEach((el) => setInlineDeclarations(el, { 'font-size': value }));
}

/**
 * Remove every declaration THIS toolbar writes from the resolved
 * paragraph + run — the "back out a bad edit" affordance.
 *
 * <p>Scoped deliberately: only {@link PARAGRAPH_PROPS} and
 * {@link RUN_PROPS} are cleared, so a reset can never strip a
 * docx-preview declaration the toolbar never touched. Page margins are
 * NOT cleared here — they are document-wide, so they get their own
 * explicit reset inside the margins popover rather than being collateral
 * damage of a per-paragraph undo.</p>
 */
export function clearFormatting(target: FormatTarget): void {
  const { paragraph, run } = target;
  if (paragraph) {
    const patch: Record<string, string | null> = {};
    for (const p of PARAGRAPH_PROPS) patch[p] = null;
    setInlineDeclarations(paragraph, patch);
    // Mirror of setParagraphFontSize — clear the run-level sizes it
    // would have stamped, so the source's class-rule size comes back.
    setParagraphFontSize(paragraph, null);
  }
  if (run && run !== paragraph) {
    setInlineDeclarations(run, { 'font-size': null });
  }
}

/**
 * Strip the active-target marker classes from the canvas.
 *
 * <p>Called from the studio's {@code resetPreviewClasses}, which runs
 * immediately before {@code canvas.innerHTML} is read in save() — so
 * the highlight is guaranteed never to enter the persisted canonical
 * HTML. An element left with an empty {@code class=""} would serialise
 * into the saved markup, so the attribute is removed outright when the
 * marker was its only class.</p>
 */
export function clearFormatHighlight(canvas: HTMLElement | null): void {
  if (!canvas) return;
  canvas
    .querySelectorAll<HTMLElement>(
      `.${FMT_ACTIVE_CLASS}, .${FMT_ACTIVE_SECTION_CLASS}`,
    )
    .forEach((el) => {
      el.classList.remove(FMT_ACTIVE_CLASS, FMT_ACTIVE_SECTION_CLASS);
      if (el.getAttribute('class') === '') el.removeAttribute('class');
    });
}

/** Paint the active-target marker on one element (clearing any prior). */
export function setFormatHighlight(
  canvas: HTMLElement | null,
  el: HTMLElement | null,
  variant: 'element' | 'section' = 'element',
): void {
  clearFormatHighlight(canvas);
  if (!el) return;
  el.classList.add(FMT_ACTIVE_CLASS);
  if (variant === 'section') el.classList.add(FMT_ACTIVE_SECTION_CLASS);
}

/** Human label for the active target, shown at the left of the strip so
 *  the admin can confirm WHAT they're about to restyle. */
export function describeTarget(target: FormatTarget): string {
  if (!target.paragraph) return 'Nothing selected';
  const text = (target.paragraph.textContent ?? '').trim().replace(/\s+/g, ' ');
  if (!text) return 'Empty paragraph';
  return text.length > 42 ? `${text.slice(0, 42)}…` : text;
}

/** Line-spacing presets — unitless ratios, which is the correct shape
 *  for line-height (it inherits proportionally rather than as a fixed
 *  length). */
export const LINE_SPACING_PRESETS = ['1', '1.15', '1.5', '2'] as const;

/** Page-margin presets mirroring Word's own menu. Every value is a
 *  SIMPLE_LENGTH token so all four always round-trip. */
export const PAGE_MARGIN_PRESETS: Array<{ label: string; margins: PageMargins }> = [
  { label: 'Normal', margins: { top: '1in', right: '1in', bottom: '1in', left: '1in' } },
  { label: 'Narrow', margins: { top: '0.5in', right: '0.5in', bottom: '0.5in', left: '0.5in' } },
  { label: 'Moderate', margins: { top: '1in', right: '0.75in', bottom: '1in', left: '0.75in' } },
  { label: 'Wide', margins: { top: '1in', right: '2in', bottom: '1in', left: '2in' } },
];
