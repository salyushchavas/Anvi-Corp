'use client';

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
} from 'react';
import { CheckCircle2, CircleDashed, Info, Lock, PenLine } from 'lucide-react';
import InlineSignatureCapture from '@/components/idms/InlineSignatureCapture';
import type { FieldSchemaEntry, InstanceDetail } from '@/lib/careers/idms';

/**
 * Field panel that lives beside the live-preview canvas. Shows a guided
 * checklist for the CURRENT user (text/date/textarea inputs, SignaturePad
 * launcher for signatures), plus locked rows for the other party's fields
 * and the AUTO-filled system fields.
 *
 * <p>Bidirectionally focus-linked with the preview via {@link
 * FieldFormHandle#focusField} (parent calls this when the preview is
 * clicked) and the {@code onFocusField} callback (fires when a panel input
 * gains focus, so the parent can scroll+highlight the anchor).</p>
 */
export interface FieldFormHandle {
  focusField(fieldId: string): void;
}

export interface FieldFormProps {
  detail: InstanceDetail;
  fields: FieldSchemaEntry[];
  role: 'ERM' | 'INTERN';
  textValues: Record<string, string>;
  onTextChange: (fieldId: string, value: string) => void;
  /** User clicked the row's Sign / Change button. Parent sets
   *  {@code activeSignatureFieldId} to this id, which flips the row into
   *  its expanded state below and renders {@link InlineSignatureCapture}
   *  in the panel flow (no portal, no floating overlay). */
  onOpenSignature: (fieldId: string) => void;
  activeSignatureFieldId?: string | null;
  focusedFieldId?: string | null;
  onFocusField?: (fieldId: string | null) => void;
  /** Signature object URLs keyed by fieldId (see {@link
   *  useSignatureBlobs}). Used for the signature-row thumbnail so the
   *  panel doesn't try to render the raw PII-encrypted URL directly. */
  signatureBlobs?: Record<string, string>;
  disabled?: boolean;
  /** Save handler for the active row's InlineSignatureCapture. Fires the
   *  same POST /sign the previous portal-mounted capture did; on
   *  resolve the parent clears {@code activeSignatureFieldId} and the
   *  inline section unmounts. On reject the inline section shows the
   *  error inline so the user can retry without redrawing. */
  onSaveSignature?: (fieldId: string, dataUrl: string, typedName: string) => Promise<void>;
  /** Cancel handler for the active row's InlineSignatureCapture — same
   *  semantics as a Save that clears {@code activeSignatureFieldId} but
   *  without a network call. Also fires when the user clicks the row's
   *  Cancel button in its header. */
  onCancelSignature?: () => void;
  /** Full name to pre-fill the InlineSignatureCapture's Generate-mode
   *  input + typed-name field. Callers pass the current user's name. */
  signerName?: string;
}

const FieldForm = forwardRef<FieldFormHandle, FieldFormProps>(
  function FieldForm(props, ref) {
    const {
      detail, fields, role, textValues, onTextChange,
      onOpenSignature, activeSignatureFieldId,
      focusedFieldId, onFocusField, signatureBlobs, disabled,
      onSaveSignature, onCancelSignature, signerName,
    } = props;

    // A ref map keyed by fieldId so the parent can focus programmatically
    // when the user clicks an anchor in the preview.
    const inputRefs = useRef<Record<string, HTMLElement | null>>({});

    useImperativeHandle(ref, () => ({
      focusField(fieldId: string) {
        const el = inputRefs.current[fieldId];
        if (!el) return;
        el.focus();
        el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      },
    }), []);

    // Field-level correction lock — when the instance carries a
    // non-null unlockedFieldIds set, only those ids are editable by
    // the intern; everything else renders read-only under a "Locked
    // by ERM" chip. The set only applies to the INTERN role (ERM
    // writes happen before the correction cycle). Null set = legacy
    // behaviour (every intern-assigneed field editable).
    const lockNarrowing = role === 'INTERN' && detail.unlockedFieldIds
      ? new Set(detail.unlockedFieldIds)
      : null;
    const mine = fields.filter((f) => f.assignee === role);
    const mineEditable = lockNarrowing == null
      ? mine
      : mine.filter((f) => lockNarrowing.has(f.id));
    const mineLocked = lockNarrowing == null
      ? []
      : mine.filter((f) => !lockNarrowing.has(f.id));
    const other = fields.filter(
      (f) => f.assignee !== role && f.assignee !== 'AUTO',
    );
    const auto = fields.filter((f) => f.assignee === 'AUTO');

    const otherLabel = role === 'ERM' ? 'the intern' : 'your ERM';

    // Completion — only the caller's REQUIRED + EDITABLE fields count
    // against send-readiness. On a narrowed correction cycle the
    // locked fields already carry their previous values and don't
    // participate in the current submit gate.
    const requiredMine = mineEditable.filter((f) => f.required);
    const requiredComplete = requiredMine.filter((f) => isFilled(f, detail, textValues)).length;
    const totalMine = mineEditable.filter((f) => isFilled(f, detail, textValues)).length;

    return (
      <div className="space-y-5">
        {mineEditable.length > 0 && (
          <SectionHeader
            label={
              lockNarrowing == null
                ? 'Your fields'
                : 'Needs correction'
            }
            done={totalMine}
            total={mineEditable.length}
            requiredDone={requiredComplete}
            requiredTotal={requiredMine.length}
          />
        )}

        {mineEditable.length === 0 && mineLocked.length === 0 ? (
          <p className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
            Nothing for you to fill on this document.
          </p>
        ) : mineEditable.length === 0 ? (
          <p className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
            ERM didn't flag any fields for correction — nothing for you to change.
          </p>
        ) : (
          <ul className="space-y-3">
            {mineEditable.map((f) => (
              <FieldRow
                key={f.id}
                field={f}
                detail={detail}
                textValues={textValues}
                onTextChange={onTextChange}
                onOpenSignature={onOpenSignature}
                activeSignatureFieldId={activeSignatureFieldId}
                focusedFieldId={focusedFieldId}
                onFocusField={onFocusField}
                signatureBlobs={signatureBlobs}
                disabled={disabled}
                onSaveSignature={onSaveSignature}
                onCancelSignature={onCancelSignature}
                signerName={signerName}
                registerRef={(el) => { inputRefs.current[f.id] = el; }}
              />
            ))}
          </ul>
        )}

        {mineLocked.length > 0 && (
          <div>
            <SectionHeader label="Locked (ERM didn't request changes)" muted />
            <ul className="mt-2 space-y-2">
              {mineLocked.map((f) => {
                const persisted = detail.values[f.id];
                const filled = isFilled(f, detail, {});
                return (
                  <LockedRow
                    key={f.id}
                    name={f.name}
                    value={filled
                      ? (persisted?.valueText
                        ?? (f.type === 'signature' ? 'Signature captured' : '—'))
                      : null}
                    chip="Locked"
                    chipTone="slate"
                    icon={<Lock className="h-3 w-3" />}
                  />
                );
              })}
            </ul>
          </div>
        )}

        {auto.length > 0 && (
          <div>
            <SectionHeader label="Filled automatically" muted />
            <ul className="mt-2 space-y-2">
              {auto.map((f) => (
                <LockedRow
                  key={f.id}
                  name={f.name}
                  value={detail.values[f.id]?.valueText ?? '—'}
                  chip="Auto"
                  chipTone="slate"
                  icon={<Info className="h-3 w-3" />}
                />
              ))}
            </ul>
          </div>
        )}

        {other.length > 0 && (
          <div>
            <SectionHeader label={`From ${otherLabel}`} muted />
            <ul className="mt-2 space-y-2">
              {other.map((f) => {
                const filled = isFilled(f, detail, {});
                return (
                  <LockedRow
                    key={f.id}
                    name={f.name}
                    value={filled
                      ? (detail.values[f.id]?.valueText
                        ?? (f.type === 'signature' ? 'Signature captured' : '—'))
                      : null}
                    chip={filled
                      ? (role === 'ERM' ? 'Filled by intern' : 'Filled by ERM')
                      : (role === 'ERM' ? 'Awaiting intern' : 'Awaiting ERM')}
                    chipTone={filled ? 'emerald' : (role === 'ERM' ? 'amber' : 'blue')}
                    icon={<Lock className="h-3 w-3" />}
                  />
                );
              })}
            </ul>
          </div>
        )}
      </div>
    );
  },
);

export default FieldForm;

// ── Row + section primitives ────────────────────────────────────────

interface FieldRowProps {
  field: FieldSchemaEntry;
  detail: InstanceDetail;
  textValues: Record<string, string>;
  onTextChange: (id: string, v: string) => void;
  onOpenSignature: (id: string) => void;
  activeSignatureFieldId?: string | null;
  focusedFieldId?: string | null;
  onFocusField?: (id: string | null) => void;
  signatureBlobs?: Record<string, string>;
  disabled?: boolean;
  onSaveSignature?: (fieldId: string, dataUrl: string, typedName: string) => Promise<void>;
  onCancelSignature?: () => void;
  signerName?: string;
  registerRef: (el: HTMLElement | null) => void;
}

function FieldRow({
  field, detail, textValues, onTextChange, onOpenSignature,
  activeSignatureFieldId, focusedFieldId, onFocusField, signatureBlobs,
  disabled, onSaveSignature, onCancelSignature, signerName, registerRef,
}: FieldRowProps) {
  const persisted = detail.values[field.id];
  const filled = isFilled(field, detail, textValues);
  const focused = focusedFieldId === field.id;

  const handleFocus = useCallback(() => {
    onFocusField?.(field.id);
  }, [field.id, onFocusField]);

  const rowRing = focused
    ? 'ring-2 ring-brand-500/40'
    : filled
      ? 'ring-1 ring-emerald-200'
      : field.required
        ? 'ring-1 ring-slate-200'
        : 'ring-1 ring-slate-200';

  const labelBadge = filled ? (
    <CheckCircle2 className="h-3 w-3 text-emerald-600" />
  ) : (
    <CircleDashed className="h-3 w-3 text-slate-400" />
  );

  if (field.type === 'signature') {
    const signed = Boolean(persisted?.signatureUrl);
    const active = activeSignatureFieldId === field.id;
    const missingRequired = field.required && !signed;
    // Signature rows are STATUS + INLINE EXPAND. The Sign/Change button
    // flips the row into an expanded state that reveals
    // InlineSignatureCapture directly below the header — in the panel
    // flow, no portal, no overlay, no float. Multi-signature: each row
    // owns its own expand independently (only the active one renders
    // the capture body). Clicking Cancel or successful Save collapses
    // back to the compact header.
    const signatureRowRing = active
      ? 'ring-2 ring-brand-500/40'
      : signed
        ? 'ring-1 ring-emerald-200'
        : missingRequired
          ? 'ring-1 ring-red-300'
          : 'ring-1 ring-slate-200';
    return (
      <li>
        <div
          data-fill-field-id={field.id}
          className={`rounded-md border border-slate-200 bg-white p-3 ${signatureRowRing}`}
        >
          <div className="flex items-center gap-2">
            {labelBadge}
            <span className="text-xs font-medium text-slate-800">
              {field.name}{field.required && <span className="text-red-500"> *</span>}
            </span>
          </div>
          <div className="mt-2 flex items-center justify-between gap-3">
            <div className="flex min-w-0 items-center gap-2 text-xs">
              {signed && signatureBlobs?.[field.id] ? (
                <img
                  src={signatureBlobs[field.id]}
                  alt="Signature"
                  className="h-6 rounded border border-emerald-200 bg-white px-1"
                />
              ) : null}
              <span
                className={
                  signed
                    ? 'text-emerald-700'
                    : active
                      ? 'text-brand-800'
                      : missingRequired
                        ? 'text-red-600'
                        : 'text-slate-500'
                }
              >
                {signed
                  ? 'Signed'
                  : active
                    ? 'Signing…'
                    : missingRequired
                      ? 'Signature required'
                      : 'Not signed'}
              </span>
            </div>
            <button
              type="button"
              ref={(el) => registerRef(el)}
              onFocus={handleFocus}
              onClick={() => {
                // Clicking the header button while expanded collapses;
                // otherwise opens. Same button, one shape — no separate
                // "Close" affordance needed in the header.
                if (active) {
                  onCancelSignature?.();
                } else {
                  onOpenSignature(field.id);
                }
              }}
              disabled={disabled}
              className={`inline-flex shrink-0 items-center gap-1 rounded-md border px-2.5 py-1 text-xs font-semibold transition disabled:opacity-60 ${
                active
                  ? 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50'
                  : signed
                    ? 'border-emerald-200 bg-white text-emerald-800 hover:bg-emerald-50'
                    : missingRequired
                      ? 'border-red-300 bg-red-600 text-white hover:bg-red-700'
                      : 'border-brand-300 bg-brand-700 text-white hover:bg-brand-800'
              }`}
            >
              <PenLine className="h-3 w-3" />
              {active ? 'Cancel' : signed ? 'Change' : 'Sign'}
            </button>
          </div>
          {!signed && !active && (
            <p className="mt-1.5 text-[11px] text-slate-500">
              Click Sign to open the signature pad below
              {missingRequired ? ' — required to send' : ''}.
            </p>
          )}
          {active && onSaveSignature && (
            // Keyed by fieldId so switching from one signature's expand
            // to another remounts InlineSignatureCapture with a clean
            // slate — no bleed of staged bytes or typed name between
            // signatures on a multi-sig doc.
            <InlineSignatureCapture
              key={field.id}
              fieldName={field.name}
              signerName={signerName}
              onCancel={() => onCancelSignature?.()}
              onSave={(dataUrl, typedName) =>
                onSaveSignature(field.id, dataUrl, typedName)}
            />
          )}
        </div>
      </li>
    );
  }

  const value = textValues[field.id] ?? '';
  const isDate = field.type === 'date';
  const isBlock = field.type === 'content_block';
  const missing = field.required && !filled;
  // Mirrors the backend @Size(max = 50_000) on FillFieldsRequest.values —
  // surface a live counter on content_block textareas that can plausibly
  // approach the cap so the user gets feedback before a submit 400s.
  const VALUE_MAX = 50_000;
  const remaining = VALUE_MAX - value.length;
  const nearLimit = isBlock && remaining <= 5_000;
  const overLimit = isBlock && remaining < 0;

  return (
    <li>
      <div className={`rounded-md border border-slate-200 bg-white p-3 ${rowRing}`}>
        <label
          htmlFor={`fld-${field.id}`}
          className="flex items-center gap-2 text-xs font-medium text-slate-800"
        >
          {labelBadge}
          {field.name}{field.required && <span className="text-red-500"> *</span>}
        </label>
        {isBlock ? (
          <>
            <textarea
              id={`fld-${field.id}`}
              ref={(el) => registerRef(el)}
              value={value}
              onChange={(e) => onTextChange(field.id, e.target.value)}
              onFocus={handleFocus}
              disabled={disabled}
              rows={4}
              maxLength={VALUE_MAX}
              className={`mt-2 w-full rounded-md border px-2 py-1.5 text-sm focus:outline-none focus:ring-1 disabled:opacity-60 ${
                overLimit
                  ? 'border-red-400 focus:border-red-500 focus:ring-red-500'
                  : nearLimit
                    ? 'border-amber-400 focus:border-amber-500 focus:ring-amber-500'
                    : 'border-slate-200 focus:border-brand-500 focus:ring-brand-500'
              }`}
            />
            {(nearLimit || value.length > 1_000) && (
              <p
                className={`mt-1 text-[11px] tabular-nums ${
                  overLimit
                    ? 'text-red-600'
                    : nearLimit
                      ? 'text-amber-600'
                      : 'text-slate-400'
                }`}
              >
                {value.length.toLocaleString()} / {VALUE_MAX.toLocaleString()} characters
                {overLimit && ' — over the limit, trim before submitting'}
              </p>
            )}
          </>
        ) : (
          <input
            id={`fld-${field.id}`}
            ref={(el) => registerRef(el)}
            type={isDate ? 'date' : 'text'}
            value={value}
            onChange={(e) => onTextChange(field.id, e.target.value)}
            onFocus={handleFocus}
            disabled={disabled}
            className="mt-2 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 disabled:opacity-60"
          />
        )}
        {isDate && !missing && (
          <p className="mt-1 text-[11px] text-slate-400">
            Shows in the document as MM/DD/YYYY.
          </p>
        )}
        {missing && (
          <p className="mt-1.5 text-[11px] text-red-600">Required</p>
        )}
      </div>
    </li>
  );
}

interface SectionHeaderProps {
  label: string;
  done?: number;
  total?: number;
  requiredDone?: number;
  requiredTotal?: number;
  muted?: boolean;
}

function SectionHeader({ label, done, total, requiredDone, requiredTotal, muted }: SectionHeaderProps) {
  const hasCount = total != null && done != null;
  const hasRequired = requiredTotal != null && requiredTotal > 0 && requiredDone != null;
  const allRequiredDone = hasRequired && requiredDone === requiredTotal;
  return (
    <div className={`flex items-center justify-between ${muted ? 'text-slate-500' : 'text-slate-800'}`}>
      <h3 className={muted ? 'text-[11px] font-medium uppercase tracking-wide' : 'text-sm font-semibold'}>
        {label}
      </h3>
      {hasCount && (
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ${
          allRequiredDone
            ? 'bg-emerald-100 text-emerald-800'
            : 'bg-slate-100 text-slate-700'
        }`}>
          {done} of {total} filled
        </span>
      )}
    </div>
  );
}

function LockedRow({
  name, value, chip, chipTone, icon,
}: {
  name: string;
  value: string | null;
  chip: string;
  chipTone: 'slate' | 'amber' | 'blue' | 'emerald';
  icon?: React.ReactNode;
}) {
  const tone: Record<typeof chipTone, string> = {
    slate:   'bg-slate-100 text-slate-700',
    amber:   'bg-amber-100 text-amber-900',
    blue:    'bg-sky-100 text-sky-800',
    emerald: 'bg-emerald-100 text-emerald-800',
  };
  return (
    <li className="rounded-md border border-slate-200 bg-slate-50/50 p-2.5">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-xs font-medium text-slate-700">
          {icon}
          {name}
        </div>
        <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-medium ${tone[chipTone]}`}>
          {chip}
        </span>
      </div>
      {value && (
        <p className="mt-1 pl-4 text-xs text-slate-500 line-clamp-2">{value}</p>
      )}
    </li>
  );
}

/** True when the field has a persisted value (or, for owner fields, a
 *  non-empty in-memory value). Signatures rely on server-persisted url. */
function isFilled(
  field: FieldSchemaEntry,
  detail: InstanceDetail,
  textValues: Record<string, string>,
): boolean {
  const persisted = detail.values[field.id];
  if (field.type === 'signature') return Boolean(persisted?.signatureUrl);
  const inMemory = textValues[field.id];
  if (inMemory != null && inMemory.trim().length > 0) return true;
  return Boolean(persisted?.valueText && persisted.valueText.trim().length > 0);
}
