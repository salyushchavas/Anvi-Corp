'use client';

import { useEffect, useMemo, useRef } from 'react';
import type { FieldSchemaEntry, InstanceDetail } from '@/lib/careers/idms';

/**
 * Renders an instance's canonical HTML with per-field visual overlays.
 * Kept dumb — the parent supplies the values map + role and this component
 * just paints them into the document.
 *
 * <p>Modes:</p>
 * <ul>
 *   <li>{@code editRole="ERM"} — ERM's fields become input surfaces
 *       (backed by parent state); INTERN fields render as amber "awaits"
 *       placeholders; AUTO fields show the resolved value inline.</li>
 *   <li>{@code editRole="INTERN"} — inverse. INTERN fields become inputs;
 *       ERM+AUTO fields render as locked filled values.</li>
 *   <li>{@code editRole=null} — read-only render (used by Verify page).</li>
 * </ul>
 */
export interface InstanceRendererProps {
  detail: InstanceDetail;
  fields: FieldSchemaEntry[];
  editRole: 'ERM' | 'INTERN' | null;
  /** Working text values, keyed by fieldId. Signature fields aren't
   *  represented here — they render as either a signature-pad control
   *  slot (edit mode) or the persisted image (read/locked mode). */
  textValues: Record<string, string>;
  onTextChange: (fieldId: string, value: string) => void;
  /** Which signature field, if any, is currently being drawn on — the
   *  page renders the SignaturePad + save button next to the doc; this
   *  callback lets the doc show a "signing…" state on that anchor. */
  activeSignatureFieldId?: string | null;
  onOpenSignature?: (fieldId: string) => void;
}

export default function InstanceRenderer(props: InstanceRendererProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const { detail, fields, editRole, textValues, onTextChange,
    activeSignatureFieldId, onOpenSignature } = props;

  const schemaById = useMemo(() => {
    const m = new Map<string, FieldSchemaEntry>();
    for (const f of fields) m.set(f.id, f);
    return m;
  }, [fields]);

  // Inject the canonical HTML once per detail change; the field overlays
  // are then applied per render via DOM patching so React doesn't fight
  // the raw HTML that docx-preview shipped.
  useEffect(() => {
    const c = containerRef.current;
    if (!c) return;
    c.innerHTML = detail.canonicalHtml ?? '';
  }, [detail.canonicalHtml]);

  useEffect(() => {
    const c = containerRef.current;
    if (!c) return;
    const spans = c.querySelectorAll<HTMLElement>('span[data-field-id]');
    spans.forEach((span) => {
      const id = span.getAttribute('data-field-id');
      if (!id) return;
      const schema = schemaById.get(id);
      const value = detail.values[id];
      // Wipe existing content on each pass so we can re-render fresh.
      span.innerHTML = '';
      span.classList.remove(
        'doc-field--erm', 'doc-field--intern', 'doc-field--auto',
        'doc-field--awaits', 'doc-field--input', 'doc-field--filled',
      );
      const assignee = (schema?.assignee ?? 'ERM') as 'ERM' | 'INTERN' | 'AUTO';
      span.classList.add(`doc-field--${assignee.toLowerCase()}`);

      if (schema?.type === 'signature') {
        // Persisted signature — show the image if one exists, else a slot.
        if (value?.signatureUrl) {
          const img = document.createElement('img');
          img.src = value.signatureUrl;
          img.alt = 'Signature';
          img.style.cssText = 'max-height:44px;vertical-align:middle;';
          span.appendChild(img);
          span.classList.add('doc-field--filled');
        } else {
          const canSign = editRole && assignee === editRole;
          const label = document.createElement('span');
          label.textContent = canSign
            ? (activeSignatureFieldId === id ? '● signing…' : '＋ sign here')
            : '— awaiting signature —';
          label.className = 'doc-field-inline-label';
          span.appendChild(label);
          if (canSign && onOpenSignature) {
            span.style.cursor = 'pointer';
            span.onclick = () => onOpenSignature(id);
          }
          span.classList.add(canSign ? 'doc-field--input' : 'doc-field--awaits');
        }
        return;
      }

      // Text-type field (text, date, content_block).
      const canEdit = editRole && assignee === editRole;
      if (canEdit) {
        const input = schema?.type === 'content_block'
          ? document.createElement('textarea')
          : document.createElement('input');
        if (schema?.type === 'date') {
          (input as HTMLInputElement).type = 'date';
        }
        (input as HTMLInputElement).value = textValues[id] ?? '';
        (input as HTMLInputElement).placeholder = schema?.name ?? '';
        input.className = 'doc-field-input';
        input.oninput = (e) => {
          onTextChange(id, (e.target as HTMLInputElement).value);
        };
        span.appendChild(input);
        span.classList.add('doc-field--input');
      } else {
        const filled = (assignee === 'AUTO' || assignee !== editRole)
          ? (value?.valueText ?? textValues[id] ?? null)
          : null;
        if (filled) {
          span.textContent = filled;
          span.classList.add('doc-field--filled');
        } else {
          const label = document.createElement('span');
          label.textContent = editRole === null
            ? `[${schema?.name ?? 'unfilled'}]`
            : `— ${assignee === 'INTERN' ? 'intern will fill' : 'awaiting'} —`;
          label.className = 'doc-field-inline-label';
          span.appendChild(label);
          span.classList.add('doc-field--awaits');
        }
      }
    });
  }, [detail, schemaById, editRole, textValues, activeSignatureFieldId, onOpenSignature]);

  return (
    <div>
      <div ref={containerRef} className="doc-canvas" />
      <style jsx global>{`
        .doc-canvas { background: white; padding: 32px 40px; border-radius: 6px; }
        .doc-canvas .docx { background: white; margin: 0 auto; }
        .doc-field { border-radius: 3px; padding: 0 2px; }
        .doc-field--erm { background: rgba(37, 99, 235, 0.10); }
        .doc-field--intern { background: rgba(245, 158, 11, 0.12); }
        .doc-field--auto { background: rgba(148, 163, 184, 0.15); }
        .doc-field--awaits { color: #94a3b8; font-style: italic; }
        .doc-field--filled { background: rgba(16, 185, 129, 0.10); }
        .doc-field-inline-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.03em; }
        .doc-field-input {
          border: 1px dashed rgba(37, 99, 235, 0.65);
          border-radius: 3px;
          padding: 2px 4px;
          font: inherit;
          min-width: 100px;
          background: white;
        }
        textarea.doc-field-input { min-height: 60px; width: 100%; }
      `}</style>
    </div>
  );
}
