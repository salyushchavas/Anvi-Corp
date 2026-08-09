'use client';

import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
} from 'react';
import { formatIsoDateMdy, type FieldSchemaEntry, type InstanceDetail } from '@/lib/careers/idms';

/**
 * IDMS live-preview renderer.
 *
 * <p>Non-editable at all times. All values flow in through {@code textValues};
 * every anchor for a given field id renders the same string so multi-anchor
 * fields stay in sync on every keystroke. Focused-field highlighting (soft
 * ring on every anchor) + click-to-focus (any anchor click fires
 * {@code onFieldClick}) make the preview and the right-side form feel like
 * one coordinated surface.</p>
 *
 * <p>PDF safety: the tint + dashed-placeholder + highlight styles are all
 * class-based and defined by this component only — they never appear in the
 * canonical HTML saved on the instance, and the openhtmltopdf CSS does not
 * include these rules. Finalized PDFs render clean.</p>
 */
export interface InstanceRendererProps {
  detail: InstanceDetail;
  fields: FieldSchemaEntry[];
  editRole: 'ERM' | 'INTERN' | null;
  /** Working text values keyed by fieldId. Every anchor of the same field
   *  id renders the same value — multi-anchor updates are free. */
  textValues: Record<string, string>;
  /** Signature object URLs by fieldId, resolved by
   *  {@link useSignatureBlobs}. Preferred over the raw
   *  {@code detail.values[id].signatureUrl} because signature bytes
   *  are PII-encrypted at rest — a direct S3 fetch would return the
   *  encrypted envelope (broken image). Falls back to the raw URL
   *  when a blob hasn't loaded yet, so pre-hydration state renders
   *  something reasonable. */
  signatureBlobs?: Record<string, string>;
  /** Field currently focused in the panel — all its anchors light up. */
  focusedFieldId?: string | null;
  /** Fired when a text/date/content_block anchor is clicked. Parent
   *  typically focuses the corresponding input in the field panel.
   *  Signature clicks go through {@link #onSignatureClick} instead so
   *  the parent gets the anchor element for popover positioning. */
  onFieldClick?: (fieldId: string) => void;
  /** Fired when the caller clicks THEIR OWN signature slot in the
   *  preview. The anchor element is provided so the parent can anchor
   *  a signature popover at the signature line — the "sign at the line
   *  in the doc" UX. Foreign-owner signature clicks still fall through
   *  to {@link #onFieldClick} (parent typically no-ops or focuses). */
  onSignatureClick?: (fieldId: string, anchorEl: HTMLElement) => void;
  /** Signature field currently being drawn on (label switches to "signing…"). */
  activeSignatureFieldId?: string | null;
}

/** Imperative surface exposed via ref — callers use this to open a
 *  signature slot programmatically (e.g. the FieldForm's "Sign" button,
 *  or Send/Submit's validate-on-click that jumps to the first missing
 *  required signature). */
export interface InstanceRendererHandle {
  /** Scroll the first anchor of {@code fieldId} into center view AND
   *  fire {@code onSignatureClick(fieldId, anchorEl)} so the parent
   *  opens the popover at the anchor. No-op if the field isn't a
   *  signature the caller owns. */
  openSignatureAt(fieldId: string): void;
}

const InstanceRenderer = forwardRef<InstanceRendererHandle, InstanceRendererProps>(
  function InstanceRenderer(props, ref) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const { detail, fields, editRole, textValues, signatureBlobs,
    focusedFieldId, onFieldClick, onSignatureClick,
    activeSignatureFieldId } = props;

  const schemaById = useMemo(() => {
    const m = new Map<string, FieldSchemaEntry>();
    for (const f of fields) m.set(f.id, f);
    return m;
  }, [fields]);

  // Inject the canonical HTML once per detail change; per-anchor overlays
  // are then applied via DOM patching so React doesn't fight the raw HTML
  // that docx-preview originally emitted.
  useEffect(() => {
    const c = containerRef.current;
    if (!c) return;
    c.innerHTML = detail.canonicalHtml ?? '';
  }, [detail.canonicalHtml]);

  // Paint the anchors.
  useEffect(() => {
    const c = containerRef.current;
    if (!c) return;
    const spans = c.querySelectorAll<HTMLElement>('span[data-field-id]');
    spans.forEach((span) => {
      const id = span.getAttribute('data-field-id');
      if (!id) return;
      const schema = schemaById.get(id);
      const persisted = detail.values[id];

      // Typography inheritance — copy the surrounding docx-preview
      // run's font-family + font-size onto the anchor BEFORE we wipe
      // its inner content, so the filled value renders in the same
      // face + size as the neighbouring template text. Cached in
      // dataset so keystroke re-paints don't hunt again.
      applyInheritedTypography(span);

      // Signature-anchor DOM reuse — when the effective src hasn't
      // changed AND an <img> for it already exists, we DON'T touch the
      // img element (previously we did innerHTML='' + createElement +
      // src= on every keystroke, which forced the browser to reload the
      // bitmap and flashed a broken-image on each character). Only the
      // outer classes (focus ring, tint) are re-applied.
      //
      // Effective src = the blob URL from useSignatureBlobs when
      // hydrated (the correct decrypted PNG), else the raw
      // detail.values[id].signatureUrl (a fallback marker — the raw
      // server URL alone can't render because signature bytes are
      // PII-encrypted at rest and need to go through the authenticated
      // /idms/documents/{...}/signatures/{...} endpoint).
      const isSignatureAnchor = schema?.type === 'signature';
      const sigSrc = isSignatureAnchor
        ? (signatureBlobs?.[id] ?? persisted?.signatureUrl ?? null)
        : null;
      const existingSigImg = isSignatureAnchor
        ? span.querySelector<HTMLImageElement>('img.doc-field-sig')
        : null;
      const reusableSig = isSignatureAnchor
        && sigSrc
        && existingSigImg
        && existingSigImg.getAttribute('data-sig-src') === sigSrc;

      if (!reusableSig) {
        // Reset per pass — we own every span's contents + classes.
        span.innerHTML = '';
      }
      span.classList.remove(
        'doc-field--erm', 'doc-field--intern', 'doc-field--auto',
        'doc-field--awaits', 'doc-field--filled', 'doc-field--signable',
        'doc-field--focused',
      );
      span.style.cursor = '';
      span.onclick = null;

      const assignee = (schema?.assignee ?? 'ERM') as 'ERM' | 'INTERN' | 'AUTO';
      span.classList.add(`doc-field--${assignee.toLowerCase()}`);
      if (focusedFieldId && focusedFieldId === id) {
        span.classList.add('doc-field--focused');
      }

      // ── Signature anchor ─────────────────────────────────────────
      if (schema?.type === 'signature') {
        const canSign = editRole !== null && assignee === editRole;
        if (persisted?.signatureUrl) {
          if (reusableSig) {
            // Existing img stays in place — no HTTP re-fetch, no flicker.
            span.classList.add('doc-field--filled');
            if (canSign) span.classList.add('doc-field--signable');
            wireSignatureClick(span, id, canSign, onSignatureClick, onFieldClick);
            return;
          }
          if (sigSrc) {
            const img = document.createElement('img');
            img.className = 'doc-field-sig';
            // Compare via data-* attr on re-paint — img.src returns the
            // resolved absolute URL, which never equals the raw stored one.
            img.setAttribute('data-sig-src', sigSrc);
            img.src = sigSrc;
            img.alt = 'Signature';
            img.style.cssText = 'max-height:44px;vertical-align:middle;';
            span.appendChild(img);
            span.classList.add('doc-field--filled');
            if (canSign) span.classList.add('doc-field--signable');
          } else {
            // Blob not hydrated yet — clean neutral placeholder rather
            // than a broken-image icon.
            const label = document.createElement('span');
            label.textContent = 'Loading signature…';
            label.className = 'doc-field-inline-label';
            span.appendChild(label);
            span.classList.add('doc-field--awaits');
          }
        } else {
          // Empty slot: render as a signature LINE the user can click on
          // (larger + more line-like than the prior tiny "＋ sign here"
          // chip). For non-owners this is a passive "awaiting" label.
          const label = document.createElement('span');
          label.className = 'doc-field-inline-label';
          if (canSign) {
            label.textContent = activeSignatureFieldId === id
              ? '✎ signing…'
              : '✎ Click to sign';
          } else {
            label.textContent = awaitingLabel(schema, assignee, editRole);
          }
          span.appendChild(label);
          span.classList.add(canSign ? 'doc-field--signable' : 'doc-field--awaits');
          if (canSign) span.classList.add('doc-field--sigslot');
        }
        wireSignatureClick(span, id, canSign, onSignatureClick, onFieldClick);
        return;
      }

      // ── Text-type anchor (text / date / content_block) ───────────
      const isOwner = editRole && assignee === editRole;
      let text: string | null;
      if (isOwner) {
        text = textValues[id] ?? persisted?.valueText ?? '';
      } else {
        text = persisted?.valueText ?? null;
      }

      if (text != null && text.length > 0) {
        // For content_block preserve newlines so the doc reads naturally.
        if (schema?.type === 'content_block') {
          const lines = text.split('\n');
          lines.forEach((line, i) => {
            if (i > 0) span.appendChild(document.createElement('br'));
            span.appendChild(document.createTextNode(line));
          });
        } else if (schema?.type === 'date') {
          // Document-interpolated date contract: MM/DD/YYYY every time,
          // matching backend IdmsDateFormat.formatIsoDateString on the PDF.
          span.textContent = formatIsoDateMdy(text);
        } else {
          span.textContent = text;
        }
        span.classList.add('doc-field--filled');
      } else {
        const label = document.createElement('span');
        label.textContent = editRole === null
          ? (schema?.name ?? 'unfilled')
          : isOwner
            ? (schema?.name ?? 'your input')
            : awaitingLabel(schema, assignee, editRole);
        label.className = 'doc-field-inline-label';
        span.appendChild(label);
        span.classList.add('doc-field--awaits');
      }

      if (onFieldClick) {
        span.style.cursor = 'pointer';
        span.onclick = () => onFieldClick(id);
      }
    });
  }, [detail, schemaById, editRole, textValues, signatureBlobs,
      focusedFieldId, activeSignatureFieldId, onFieldClick, onSignatureClick]);

  // Scroll the first anchor for the focused field into view — soft, block:'center'.
  useEffect(() => {
    if (!focusedFieldId) return;
    const c = containerRef.current;
    if (!c) return;
    const first = c.querySelector<HTMLElement>(
      `span[data-field-id="${cssEscape(focusedFieldId)}"]`,
    );
    if (first) first.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [focusedFieldId]);

  // Imperative openSignatureAt — the FieldForm's "Sign" button and the
  // Send/Submit validate-on-click both go through here to jump the user
  // straight to signing at the line. Scrolls the anchor to center-view
  // then fires onSignatureClick (parent opens the popover). Waits one
  // frame so the scroll has painted before the popover measures the
  // anchor's rect for positioning.
  useImperativeHandle(ref, () => ({
    openSignatureAt(fieldId: string) {
      const c = containerRef.current;
      if (!c || !onSignatureClick) return;
      const first = c.querySelector<HTMLElement>(
        `span[data-field-id="${cssEscape(fieldId)}"]`,
      );
      if (!first) return;
      first.scrollIntoView({ behavior: 'smooth', block: 'center' });
      requestAnimationFrame(() => {
        onSignatureClick(fieldId, first);
      });
    },
  }), [onSignatureClick]);

  return (
    <div>
      <div ref={containerRef} className="doc-canvas" />
      <style jsx global>{`
        .doc-canvas { background: white; padding: 32px 40px; border-radius: 6px; }
        .doc-canvas .docx { background: white; margin: 0 auto; }
        .doc-field {
          border-radius: 3px;
          padding: 0 2px;
          transition: box-shadow 120ms ease, background-color 120ms ease;
        }
        /* Owner-tinted background on filled — disappears in PDF because the
           PDF CSS doesn't define these variants. */
        .doc-field--erm.doc-field--filled { background: rgba(37, 99, 235, 0.10); }
        .doc-field--intern.doc-field--filled { background: rgba(245, 158, 11, 0.12); }
        .doc-field--auto.doc-field--filled { background: rgba(148, 163, 184, 0.15); }
        /* Unfilled placeholder — subtle dashed border + name in muted text. */
        .doc-field--awaits {
          border: 1px dashed rgba(148, 163, 184, 0.7);
          background: rgba(248, 250, 252, 0.7);
          color: #94a3b8;
        }
        .doc-field--erm.doc-field--awaits {
          border-color: rgba(37, 99, 235, 0.35);
          color: rgba(37, 99, 235, 0.75);
        }
        .doc-field--intern.doc-field--awaits {
          border-color: rgba(245, 158, 11, 0.45);
          color: rgba(180, 83, 9, 0.85);
        }
        .doc-field--signable {
          border: 1px dashed rgba(37, 99, 235, 0.55);
          background: rgba(59, 130, 246, 0.08);
          color: rgba(37, 99, 235, 0.9);
        }
        /* Empty owner signature slot — reads as a signature LINE that
           invites a click, not a text placeholder. Solid underline mimics
           the paper-document convention; hover deepens the tint so it's
           obvious the slot is interactive. Never present in the PDF
           (class doesn't cross the renderer boundary). */
        .doc-field--sigslot {
          display: inline-block;
          min-width: 180px;
          padding: 4px 10px;
          border: 1px solid rgba(37, 99, 235, 0.4);
          border-bottom: 2px solid rgba(37, 99, 235, 0.7);
          background: rgba(59, 130, 246, 0.06);
          border-radius: 4px;
          color: rgba(29, 78, 216, 0.95);
          text-align: center;
          transition: background-color 120ms ease, box-shadow 120ms ease,
                      border-color 120ms ease;
        }
        .doc-field--sigslot:hover {
          background: rgba(59, 130, 246, 0.14);
          border-color: rgba(37, 99, 235, 0.75);
          box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
        }
        /* Focused: soft ring across every anchor of the focused field. */
        .doc-field--focused {
          box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.35);
          background: rgba(37, 99, 235, 0.12);
        }
        .doc-field-inline-label {
          font-size: 11px;
          text-transform: uppercase;
          letter-spacing: 0.03em;
        }
      `}</style>
    </div>
  );
});

export default InstanceRenderer;

/** Attach the click handler for a signature anchor. Owner-clicks fire
 *  {@code onSignatureClick} (parent opens the in-preview popover);
 *  foreign clicks fall through to {@code onFieldClick} so the parent can
 *  still focus the corresponding row in the FieldForm. Assigning the
 *  handler resets any prior binding so re-paints don't stack listeners. */
function wireSignatureClick(
  span: HTMLElement,
  fieldId: string,
  canSign: boolean,
  onSignatureClick: ((fieldId: string, anchorEl: HTMLElement) => void) | undefined,
  onFieldClick: ((fieldId: string) => void) | undefined,
): void {
  if (canSign && onSignatureClick) {
    span.style.cursor = 'pointer';
    span.onclick = (e) => {
      e.stopPropagation();
      onSignatureClick(fieldId, span);
    };
    return;
  }
  if (onFieldClick) {
    span.style.cursor = 'pointer';
    span.onclick = () => onFieldClick(fieldId);
  }
}

/** Pick the "awaiting" placeholder copy for a foreign-owner anchor. */
function awaitingLabel(
  schema: FieldSchemaEntry | undefined,
  assignee: 'ERM' | 'INTERN' | 'AUTO',
  editRole: 'ERM' | 'INTERN' | null,
): string {
  if (assignee === 'AUTO') return schema?.name ?? 'auto-filled';
  if (editRole === 'INTERN' && assignee === 'ERM') return 'awaiting your ERM';
  if (editRole === 'ERM' && assignee === 'INTERN') return 'awaiting the intern';
  return schema?.name ?? 'unfilled';
}

/**
 * Copy the effective font-family + font-size (plus weight / style /
 * letter-spacing / line-height so bold and italic runs stay bold and
 * italic when filled) from the anchor's surrounding docx-preview run
 * onto the anchor itself. Idempotent per anchor via {@code data-df-inherit}
 * — first paint captures, subsequent paints skip.
 *
 * <p>Priority order (first match wins):</p>
 * <ol>
 *   <li>First inner element (recursively descending into structural
 *       blocks) still present from the initial canonical HTML injection
 *       — this IS the docx run the anchor was wrapped around when the
 *       studio saved it, so its computed font is exactly what the
 *       neighbouring text uses. content_block anchors wrap whole
 *       paragraphs / lists whose direct children are unstyled block
 *       tags ({@code <p>}, {@code <ul>}, {@code <li>}); the actual
 *       run {@code <span>} carrying {@code font-family} lives deeper,
 *       so the descent looks there.</li>
 *   <li>Nearest previous / next element sibling in the same paragraph
 *       (skipping other field anchors, which have themselves been
 *       stripped of their inner runs on this same paint pass).</li>
 *   <li>Parent element — the paragraph itself — as a last resort.</li>
 * </ol>
 */
function applyInheritedTypography(span: HTMLElement): void {
  if (span.dataset.dfInherit === '1') return;
  const source = pickTypographySource(span);
  if (!source) return;
  const cs = window.getComputedStyle(source);
  if (cs.fontFamily) span.style.fontFamily = cs.fontFamily;
  if (cs.fontSize) span.style.fontSize = cs.fontSize;
  if (cs.fontWeight) span.style.fontWeight = cs.fontWeight;
  if (cs.fontStyle) span.style.fontStyle = cs.fontStyle;
  if (cs.letterSpacing) span.style.letterSpacing = cs.letterSpacing;
  if (cs.lineHeight) span.style.lineHeight = cs.lineHeight;
  span.dataset.dfInherit = '1';
}

/** Walk descendants depth-first; prefer any element whose inline
 *  {@code style} attribute contains a {@code font-family} declaration
 *  (that's a docx-preview run). If none found, return the first plain
 *  element descendant so at least paragraph-level cascading applies.
 *  Skips other field anchors so we never inherit a hoisted sibling
 *  style. */
function firstStyledDescendant(root: HTMLElement): HTMLElement | null {
  let fallback: HTMLElement | null = null;
  const walk = (el: HTMLElement): HTMLElement | null => {
    for (const child of Array.from(el.children)) {
      if (!(child instanceof HTMLElement)) continue;
      if (child.hasAttribute('data-field-id')) continue;
      if (child.classList.contains('doc-field-inline-label')) continue;
      const inlineStyle = child.getAttribute('style') ?? '';
      if (/font-family\s*:/i.test(inlineStyle)) return child;
      if (!fallback) fallback = child;
      const deeper = walk(child);
      if (deeper) return deeper;
    }
    return null;
  };
  return walk(root) ?? fallback;
}

function pickTypographySource(span: HTMLElement): HTMLElement | null {
  const inner = firstStyledDescendant(span);
  if (inner) return inner;
  let sib: Element | null = span.previousElementSibling;
  while (sib) {
    if (sib instanceof HTMLElement && !sib.hasAttribute('data-field-id')) {
      const nested = firstStyledDescendant(sib);
      return nested ?? sib;
    }
    sib = sib.previousElementSibling;
  }
  sib = span.nextElementSibling;
  while (sib) {
    if (sib instanceof HTMLElement && !sib.hasAttribute('data-field-id')) {
      const nested = firstStyledDescendant(sib);
      return nested ?? sib;
    }
    sib = sib.nextElementSibling;
  }
  return span.parentElement;
}

/** Minimal CSS.escape polyfill for older browsers — field ids are UUIDs so
 *  the dash-heavy shape is what matters. */
function cssEscape(v: string): string {
  if (typeof window !== 'undefined' && typeof (window as unknown as {
    CSS?: { escape?: (s: string) => string };
  }).CSS?.escape === 'function') {
    return (window as unknown as { CSS: { escape: (s: string) => string } })
      .CSS.escape(v);
  }
  return v.replace(/[^a-zA-Z0-9_-]/g, '\\$&');
}
