'use client';

import { useState } from 'react';
import api from '@/lib/api';
import type {
  UpdateWorkAuthRequest,
  WorkAuthCard,
} from './types';

const TYPES = [
  'US_CITIZEN',
  'PERMANENT_RESIDENT',
  'F1_CPT',
  'F1_OPT',
  'F1_STEM_OPT',
  'H1B',
  'OTHER',
];

type Props = {
  userId: string;
  initial: WorkAuthCard | null;
  onClose: () => void;
  onSaved: (updated: WorkAuthCard) => void;
};

export default function UpdateWorkAuthModal({
  userId,
  initial,
  onClose,
  onSaved,
}: Props) {
  const [form, setForm] = useState<UpdateWorkAuthRequest>({
    workAuthType: initial?.workAuthType ?? 'OTHER',
    authorizedFrom: initial?.authorizedFrom ?? undefined,
    authorizedUntil: initial?.authorizedUntil ?? undefined,
    eadExpiration: initial?.eadExpiration ?? undefined,
    i20Expiration: initial?.i20Expiration ?? undefined,
    i983Required: initial?.i983Required ?? false,
    dsoName: initial?.dsoName ?? undefined,
    dsoEmail: initial?.dsoEmail ?? undefined,
    dsoPhone: initial?.dsoPhone ?? undefined,
    ermNotes: initial?.ermNotes ?? undefined,
  });
  const [eadPlain, setEadPlain] = useState('');
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSaving(true);
    setErr(null);
    try {
      const body: UpdateWorkAuthRequest = {
        ...form,
        eadCardNumber: eadPlain.trim() || undefined,
      };
      const res = await api.post<WorkAuthCard>(
        `/api/v1/erm/compliance/interns/${userId}/work-auth`,
        body,
      );
      onSaved(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-xl rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">
            {initial ? 'Update' : 'Record'} work authorization
          </h3>
        </div>
        <div className="grid grid-cols-1 gap-3 px-5 py-4 sm:grid-cols-2">
          <Field label="Type">
            <select
              value={form.workAuthType ?? ''}
              onChange={(e) =>
                setForm({ ...form, workAuthType: e.target.value })
              }
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            >
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </Field>
          <Field label="I-983 required">
            <input
              type="checkbox"
              checked={!!form.i983Required}
              onChange={(e) =>
                setForm({ ...form, i983Required: e.target.checked })
              }
            />
          </Field>
          <Field label="Authorized from">
            <DateInput
              value={form.authorizedFrom}
              onChange={(v) => setForm({ ...form, authorizedFrom: v })}
            />
          </Field>
          <Field label="Authorized until">
            <DateInput
              value={form.authorizedUntil}
              onChange={(v) => setForm({ ...form, authorizedUntil: v })}
            />
          </Field>
          <Field label="EAD expiration">
            <DateInput
              value={form.eadExpiration}
              onChange={(v) => setForm({ ...form, eadExpiration: v })}
            />
          </Field>
          <Field label="I-20 expiration">
            <DateInput
              value={form.i20Expiration}
              onChange={(v) => setForm({ ...form, i20Expiration: v })}
            />
          </Field>
          <Field label="EAD card # (plaintext on wire)">
            <input
              value={eadPlain}
              onChange={(e) => setEadPlain(e.target.value)}
              placeholder={initial?.eadCardNumberMasked ?? 'Encrypted at rest'}
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
          </Field>
          <Field label="DSO name">
            <TextInput
              value={form.dsoName}
              onChange={(v) => setForm({ ...form, dsoName: v })}
            />
          </Field>
          <Field label="DSO email">
            <TextInput
              value={form.dsoEmail}
              onChange={(v) => setForm({ ...form, dsoEmail: v })}
            />
          </Field>
          <Field label="DSO phone">
            <TextInput
              value={form.dsoPhone}
              onChange={(v) => setForm({ ...form, dsoPhone: v })}
            />
          </Field>
          <div className="sm:col-span-2">
            <Field label="ERM notes (internal only)">
              <textarea
                rows={2}
                value={form.ermNotes ?? ''}
                onChange={(e) =>
                  setForm({ ...form, ermNotes: e.target.value })
                }
                className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
              />
            </Field>
          </div>
          {err && (
            <p className="sm:col-span-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
              {err}
            </p>
          )}
        </div>
        <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={saving}
            onClick={() => void submit()}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block text-xs font-medium text-slate-700">
      {label}
      <div className="mt-1">{children}</div>
    </label>
  );
}

function DateInput({
  value,
  onChange,
}: {
  value: string | undefined;
  onChange: (v: string | undefined) => void;
}) {
  return (
    <input
      type="date"
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || undefined)}
      className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
    />
  );
}

function TextInput({
  value,
  onChange,
}: {
  value: string | undefined;
  onChange: (v: string | undefined) => void;
}) {
  return (
    <input
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || undefined)}
      className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
    />
  );
}
