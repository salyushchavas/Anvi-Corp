'use client';

import { useEffect, useMemo, useRef } from 'react';
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
  /** Field currently focused in the panel — all its anchors light up. */
  focusedFieldId?: string | null;
  /** Fired when any anchor is clicked (text or signature). Parent typically
   *  focuses the corresponding input in the field panel. */
  onFieldClick?: (fieldId: string) => void;
  /** Signature field currently being drawn on (label switches to "signing…"). */
  activeSignatureFieldId?: string | null;
}

export default function InstanceRenderer(props: InstanceRendererProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const { detail, fields, editRole, textValues,
    focusedFieldId, onFieldClick, activeSignatureFieldId } = props;

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

      // Signature-anchor DOM reuse — when the persisted URL hasn't
      // changed AND an <img> for it already exists, we DON'T touch the
      // img element (previously we did innerHTML='' + createElement +
      // src= on every keystroke, which forced the browser to reload the
      // bitmap and flashed a broken-image on each character). Only the
      // outer classes (focus ring, tint) are re-applied.
      const isSignatureAnchor = schema?.type === 'signature';
      const existingSigImg = isSignatureAnchor
        ? span.querySelector<HTMLImageElement>('img.doc-field-sig')
        : null;
      const reusableSig = isSignatureAnchor
        && persisted?.signatureUrl
        && existingSigImg
        && existingSigImg.getAttribute('data-sig-src') === persisted.signatureUrl;

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
        if (persisted?.signatureUrl) {
          if (reusableSig) {
            // Existing img stays in place — no HTTP re-fetch, no flicker.
            span.classList.add('doc-field--filled');
            if (onFieldClick) {
              span.style.cursor = 'pointer';
              span.onclick = () => onFieldClick(id);
            }
            return;
          }
          const img = document.createElement('img');
          img.className = 'doc-field-sig';
          // Compare via data-* attr on re-paint — img.src returns the
          // resolved absolute URL, which never equals the raw stored one.
          img.setAttribute('data-sig-src', persisted.signatureUrl);
          img.src = persisted.signatureUrl;
          img.alt = 'Signature';
          img.style.cssText = 'max-height:44px;vertical-align:middle;';
          span.appendChild(img);
          span.classList.add('doc-field--filled');
        } else {
          const canSign = editRole && assignee === editRole;
          const label = document.createElement('span');
          label.textContent = canSign
            ? (activeSignatureFieldId === id ? '● signing…' : '＋ sign here')
            : awaitingLabel(schema, assignee, editRole);
          label.className = 'doc-field-inline-label';
          span.appendChild(label);
          span.classList.add(canSign ? 'doc-field--signable' : 'doc-field--awaits');
        }
        if (onFieldClick) {
          span.style.cursor = 'pointer';
          span.onclick = () => onFieldClick(id);
        }
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
  }, [detail, schemaById, editRole, textValues,
      focusedFieldId, activeSignatureFieldId, onFieldClick]);

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
