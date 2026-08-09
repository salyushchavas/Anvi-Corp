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
  onOpenSignature: (fieldId: string) => void;
  activeSignatureFieldId?: string | null;
  focusedFieldId?: string | null;
  onFocusField?: (fieldId: string | null) => void;
  /** Signature object URLs keyed by fieldId (see {@link
   *  useSignatureBlobs}). Used for the signature-row thumbnail so the
   *  panel doesn't try to render the raw PII-encrypted URL directly. */
  signatureBlobs?: Record<string, string>;
  disabled?: boolean;
}

const FieldForm = forwardRef<FieldFormHandle, FieldFormProps>(
  function FieldForm(props, ref) {
    const {
      detail, fields, role, textValues, onTextChange,
      onOpenSignature, activeSignatureFieldId,
      focusedFieldId, onFocusField, signatureBlobs, disabled,
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

    const mine = fields.filter((f) => f.assignee === role);
    const other = fields.filter(
      (f) => f.assignee !== role && f.assignee !== 'AUTO',
    );
    const auto = fields.filter((f) => f.assignee === 'AUTO');

    const otherLabel = role === 'ERM' ? 'the intern' : 'your ERM';

    // Completion — only the caller's REQUIRED fields count against
    // send-readiness; optional caller fields are informational only.
    const requiredMine = mine.filter((f) => f.required);
    const requiredComplete = requiredMine.filter((f) => isFilled(f, detail, textValues)).length;
    const totalMine = mine.filter((f) => isFilled(f, detail, textValues)).length;

    return (
      <div className="space-y-5">
        {mine.length > 0 && (
          <SectionHeader
            label={role === 'ERM' ? 'Your fields' : 'Your fields'}
            done={totalMine}
            total={mine.length}
            requiredDone={requiredComplete}
            requiredTotal={requiredMine.length}
          />
        )}

        {mine.length === 0 ? (
          <p className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
            Nothing for you to fill on this document.
          </p>
        ) : (
          <ul className="space-y-3">
            {mine.map((f) => (
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
                registerRef={(el) => { inputRefs.current[f.id] = el; }}
              />
            ))}
          </ul>
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
  registerRef: (el: HTMLElement | null) => void;
}

function FieldRow({
  field, detail, textValues, onTextChange, onOpenSignature,
  activeSignatureFieldId, focusedFieldId, onFocusField, signatureBlobs,
  disabled, registerRef,
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
    // Bolder container ring when a required signature is missing —
    // matches the text-field "Required" pattern (red text below) but
    // adds a red border so an empty signature isn't just a plain
    // button lost in a list of filled rows. Cleared once signed.
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
          <button
            type="button"
            ref={(el) => registerRef(el)}
            onFocus={handleFocus}
            onClick={() => onOpenSignature(field.id)}
            disabled={disabled}
            className={`mt-2 flex w-full items-center justify-between gap-2 rounded-md border px-3 py-2 text-xs font-medium disabled:opacity-60 ${
              signed
                ? 'border-emerald-200 bg-emerald-50 text-emerald-900 hover:bg-emerald-100'
                : active
                  ? 'border-brand-500 bg-brand-50 text-brand-800'
                  : missingRequired
                    ? 'border-red-300 bg-red-50 text-red-800 hover:bg-red-100'
                    : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
            }`}
          >
            <span className="flex items-center gap-2">
              <PenLine className="h-3.5 w-3.5" />
              {signed
                ? 'Signature saved — click to re-sign'
                : active
                  ? 'Signing…'
                  : missingRequired
                    ? 'Add your signature'
                    : 'Draw signature'}
            </span>
            {signed && signatureBlobs?.[field.id] && (
              <img
                src={signatureBlobs[field.id]}
                alt="Signature"
                className="h-6"
              />
            )}
          </button>
          {/* Mirror the text-field "Required" affordance — makes the
              empty required signature visually consistent with an empty
              required text field so the user's eye catches it while
              scanning the checklist. */}
          {missingRequired && !active && (
            <p className="mt-1.5 text-[11px] text-red-600">
              Signature required — click to sign
            </p>
          )}
        </div>
      </li>
    );
  }

  const value = textValues[field.id] ?? '';
  const isDate = field.type === 'date';
  const isBlock = field.type === 'content_block';
  const missing = field.required && !filled;

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
          <textarea
            id={`fld-${field.id}`}
            ref={(el) => registerRef(el)}
            value={value}
            onChange={(e) => onTextChange(field.id, e.target.value)}
            onFocus={handleFocus}
            disabled={disabled}
            rows={4}
            className="mt-2 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 disabled:opacity-60"
          />
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
