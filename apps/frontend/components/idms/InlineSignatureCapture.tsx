'use client';

import { useEffect, useState } from 'react';
import { CheckCircle2, Loader2, X } from 'lucide-react';
import SignatureCapture from '@/components/idms/SignatureCapture';

/**
 * Inline signature capture — the 4-mode {@link SignatureCapture} plus a
 * typed-name input and Save/Cancel actions, rendered as an in-flow
 * expandable section beneath a Signature field row in the FieldForm
 * panel.
 *
 * <p>Not a portal. Not fixed-position. Not an overlay. The container is
 * a plain block that sits in normal document flow — subsequent field
 * rows in the panel get pushed down by its height while it's expanded.
 * Collapsing (Save success or Cancel) is driven by the parent clearing
 * {@code activeSignatureFieldId}; when this component unmounts the
 * panel flow recloses.</p>
 *
 * <p>Local state (staged bytes + typed name + saving flag + error
 * banner) lives inside this component so each signature field's
 * expansion is independent of every other signature field — a
 * multi-signature doc can have one expanded without leaking staged
 * bytes into a sibling that hasn't been touched yet.</p>
 */
export interface InlineSignatureCaptureProps {
  /** Displayed as the header (e.g. "Employee Signature"). Helps the
   *  user know which slot they're signing when the doc has multiple. */
  fieldName?: string;
  /** Pre-fills the Generate-mode name input AND the typed-name field.
   *  Callers pass the current user's full name. */
  signerName?: string;
  onCancel(): void;
  /** Save handler — receives the staged PNG data URL + typed name.
   *  Returns a promise so we show a spinner and defer the parent's
   *  close until the POST /sign completes. Parent nulls the active
   *  fieldId on resolve → we unmount. On reject we stay expanded and
   *  show the error text so the user can retry without redrawing. */
  onSave(dataUrl: string, typedName: string): Promise<void>;
}

export default function InlineSignatureCapture({
  fieldName, signerName, onCancel, onSave,
}: InlineSignatureCaptureProps) {
  const [staged, setStaged] = useState<string | null>(null);
  const [typedName, setTypedName] = useState(signerName ?? '');
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // If the parent reopens the capture on a different row we get remounted
  // fresh (React key = fieldId in the parent). But if signerName ever
  // changes for the same mount, keep the typed name in sync with it as
  // long as the user hasn't started editing.
  useEffect(() => {
    setTypedName((prev) => (prev === '' ? (signerName ?? '') : prev));
  }, [signerName]);

  const canSave = Boolean(staged) && !saving;

  async function handleSave() {
    if (!staged || saving) return;
    setSaving(true);
    setErr(null);
    try {
      await onSave(staged, typedName);
      // Parent clears activeSignatureFieldId → we unmount; nothing to do.
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Signature save failed');
      setSaving(false);
    }
  }

  return (
    <div className="mt-3 rounded-md border border-brand-200 bg-brand-50/40 p-3">
      <header className="mb-2 flex items-start justify-between gap-2">
        <div>
          <h4 className="text-xs font-semibold text-slate-900">
            {fieldName ? `Sign: ${fieldName}` : 'Your signature'}
          </h4>
          <p className="mt-0.5 text-[11px] text-slate-500">
            Draw, upload, generate, or clean an image — the tabs below
            switch modes. Save to attach.
          </p>
        </div>
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          aria-label="Close signature capture"
          className="text-slate-400 hover:text-slate-700 disabled:opacity-50"
        >
          <X className="h-4 w-4" />
        </button>
      </header>

      <SignatureCapture
        initialName={signerName}
        onChange={setStaged}
        disabled={saving}
      />

      <div className="mt-3">
        <label className="text-xs font-medium text-slate-600">
          Typed name (for the record)
        </label>
        <input
          value={typedName}
          onChange={(e) => setTypedName(e.target.value)}
          disabled={saving}
          placeholder="Your name"
          className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 disabled:opacity-60"
        />
      </div>

      {err && (
        <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}

      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={handleSave}
          disabled={!canSave}
          className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
        >
          {saving
            ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
            : <CheckCircle2 className="h-3.5 w-3.5" />}
          Save signature
        </button>
      </div>
    </div>
  );
}
