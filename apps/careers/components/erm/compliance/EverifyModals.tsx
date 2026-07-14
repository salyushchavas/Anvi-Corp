'use client';

import { useState } from 'react';
import api from '@/lib/api';
import type {
  EverifyCard,
  RecordEverifyRequest,
  UpdateEverifyStatusRequest,
} from './types';

const STATUSES = [
  'PENDING_SUBMISSION',
  'OPEN',
  'EMPLOYMENT_AUTHORIZED',
  'TENTATIVE_NONCONFIRMATION',
  'FINAL_NONCONFIRMATION',
  'CLOSED',
];

const CLOSURE_REASONS = [
  'EMPLOYMENT_AUTHORIZED',
  'SELF_TERMINATED',
  'EMPLOYER_TERMINATED',
  'INVALID_QUERY',
  'OTHER',
];

const PHOTO_MATCH = ['NOT_REQUIRED', 'MATCH', 'NO_MATCH'];

export function RecordEverifyModal({
  i9FormId,
  onClose,
  onSaved,
}: {
  i9FormId: string;
  onClose: () => void;
  onSaved: (updated: EverifyCard) => void;
}) {
  const [form, setForm] = useState<RecordEverifyRequest>({
    i9FormId,
    status: 'OPEN',
    photoMatchRequired: false,
  });
  const [casePlain, setCasePlain] = useState('');
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSaving(true);
    setErr(null);
    try {
      const body: RecordEverifyRequest = {
        ...form,
        caseNumber: casePlain.trim() || undefined,
      };
      const res = await api.post<EverifyCard>(
        '/api/v1/erm/compliance/everify-cases',
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
    <Shell
      title="Record E-Verify case"
      subtitle="Logged from a separate federal E-Verify session — encrypted at rest, masked in UI."
      saving={saving}
      err={err}
      onClose={onClose}
      onSave={submit}
      saveLabel="Open case"
    >
      <Field label="Case number (plaintext on wire)">
        <input
          value={casePlain}
          onChange={(e) => setCasePlain(e.target.value)}
          placeholder="E••••1234"
          className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        />
      </Field>
      <Field label="Initial status">
        <select
          value={form.status ?? 'OPEN'}
          onChange={(e) => setForm({ ...form, status: e.target.value })}
          className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Due by">
        <DateF
          value={form.dueBy}
          onChange={(v) => setForm({ ...form, dueBy: v })}
        />
      </Field>
      <Field label="Expected close by">
        <DateF
          value={form.expectedCloseBy}
          onChange={(v) => setForm({ ...form, expectedCloseBy: v })}
        />
      </Field>
      <Field label="Photo match required">
        <input
          type="checkbox"
          checked={!!form.photoMatchRequired}
          onChange={(e) =>
            setForm({ ...form, photoMatchRequired: e.target.checked })
          }
        />
      </Field>
      <Field label="ERM notes (internal)">
        <textarea
          rows={2}
          value={form.ermNotes ?? ''}
          onChange={(e) => setForm({ ...form, ermNotes: e.target.value })}
          className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        />
      </Field>
    </Shell>
  );
}

export function UpdateEverifyStatusModal({
  caseId,
  initial,
  onClose,
  onSaved,
}: {
  caseId: string;
  initial: EverifyCard | null;
  onClose: () => void;
  onSaved: (updated: EverifyCard) => void;
}) {
  const [form, setForm] = useState<UpdateEverifyStatusRequest>({
    status: initial?.status ?? 'OPEN',
    closureReason: initial?.closureReason ?? undefined,
    expectedCloseBy: initial?.expectedCloseBy ?? undefined,
    photoMatchResult: initial?.photoMatchResult ?? undefined,
    ermNotes: initial?.ermNotes ?? undefined,
  });
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSaving(true);
    setErr(null);
    try {
      const res = await api.post<EverifyCard>(
        `/api/v1/erm/compliance/everify-cases/${caseId}/status`,
        form,
      );
      onSaved(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  const requiresClosure = ['FINAL_NONCONFIRMATION', 'CLOSED'].includes(
    form.status,
  );

  return (
    <Shell
      title="Update E-Verify status"
      saving={saving}
      err={err}
      onClose={onClose}
      onSave={submit}
      saveLabel="Save"
    >
      <Field label="Status">
        <select
          value={form.status}
          onChange={(e) => setForm({ ...form, status: e.target.value })}
          className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </Field>
      {requiresClosure && (
        <Field label="Closure reason">
          <select
            value={form.closureReason ?? ''}
            onChange={(e) =>
              setForm({
                ...form,
                closureReason: e.target.value || undefined,
              })
            }
            className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
          >
            <option value="">—</option>
            {CLOSURE_REASONS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>
      )}
      <Field label="Expected close by">
        <DateF
          value={form.expectedCloseBy}
          onChange={(v) => setForm({ ...form, expectedCloseBy: v })}
        />
      </Field>
      <Field label="Photo match result">
        <select
          value={form.photoMatchResult ?? ''}
          onChange={(e) =>
            setForm({
              ...form,
              photoMatchResult: e.target.value || undefined,
            })
          }
          className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        >
          <option value="">—</option>
          {PHOTO_MATCH.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </Field>
      <Field label="ERM notes (internal)">
        <textarea
          rows={2}
          value={form.ermNotes ?? ''}
          onChange={(e) =>
            setForm({ ...form, ermNotes: e.target.value })
          }
          className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        />
      </Field>
    </Shell>
  );
}

function Shell({
  title,
  subtitle,
  children,
  err,
  saving,
  saveLabel,
  onClose,
  onSave,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  err: string | null;
  saving: boolean;
  saveLabel: string;
  onClose: () => void;
  onSave: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
          {subtitle && (
            <p className="text-xs text-slate-500">{subtitle}</p>
          )}
        </div>
        <div className="grid grid-cols-1 gap-3 px-5 py-4">
          {children}
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
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
            onClick={() => onSave()}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {saving ? 'Saving…' : saveLabel}
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

function DateF({
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
