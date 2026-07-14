'use client';

import { useState } from 'react';
import api from '@/lib/api';
import type { I9TimelineCard, RecordI9Section2Request } from './types';

type Props = {
  userId: string;
  initialFirstDay: string | null;
  onClose: () => void;
  onSaved: (updated: I9TimelineCard) => void;
};

export default function RecordI9Section2Modal({
  userId,
  initialFirstDay,
  onClose,
  onSaved,
}: Props) {
  const [docPath, setDocPath] = useState<'A' | 'BC'>('A');
  const [form, setForm] = useState<RecordI9Section2Request>({
    firstDayOfEmployment: initialFirstDay ?? undefined,
  });
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSaving(true);
    setErr(null);
    try {
      const body: RecordI9Section2Request =
        docPath === 'A'
          ? {
              firstDayOfEmployment: form.firstDayOfEmployment,
              listATitle: form.listATitle,
              listAIssuingAuthority: form.listAIssuingAuthority,
              listADocumentNumber: form.listADocumentNumber,
              listAExpirationDate: form.listAExpirationDate,
              employerName: form.employerName,
              employerTitle: form.employerTitle,
              businessOrganizationName: form.businessOrganizationName,
              businessAddress: form.businessAddress,
            }
          : {
              firstDayOfEmployment: form.firstDayOfEmployment,
              listBTitle: form.listBTitle,
              listBIssuingAuthority: form.listBIssuingAuthority,
              listBDocumentNumber: form.listBDocumentNumber,
              listBExpirationDate: form.listBExpirationDate,
              listCTitle: form.listCTitle,
              listCIssuingAuthority: form.listCIssuingAuthority,
              listCDocumentNumber: form.listCDocumentNumber,
              employerName: form.employerName,
              employerTitle: form.employerTitle,
              businessOrganizationName: form.businessOrganizationName,
              businessAddress: form.businessAddress,
            };
      const res = await api.post<I9TimelineCard>(
        `/api/v1/erm/compliance/interns/${userId}/i9-section2`,
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
      <div className="w-full max-w-2xl rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">
            Record I-9 Section 2
          </h3>
          <p className="text-xs text-slate-500">
            Federal rule: complete within 3 business days of the first day of
            employment.
          </p>
        </div>
        <div className="grid grid-cols-1 gap-3 px-5 py-4 sm:grid-cols-2">
          <Field label="First day of employment">
            <input
              type="date"
              value={form.firstDayOfEmployment ?? ''}
              onChange={(e) =>
                setForm({
                  ...form,
                  firstDayOfEmployment: e.target.value || undefined,
                })
              }
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
          </Field>
          <Field label="Document path">
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setDocPath('A')}
                className={
                  'rounded-md border px-3 py-1.5 text-xs font-medium ' +
                  (docPath === 'A'
                    ? 'border-brand-700 bg-brand-700 text-white'
                    : 'border-slate-200 text-slate-700')
                }
              >
                List A
              </button>
              <button
                type="button"
                onClick={() => setDocPath('BC')}
                className={
                  'rounded-md border px-3 py-1.5 text-xs font-medium ' +
                  (docPath === 'BC'
                    ? 'border-brand-700 bg-brand-700 text-white'
                    : 'border-slate-200 text-slate-700')
                }
              >
                List B + List C
              </button>
            </div>
          </Field>

          {docPath === 'A' ? (
            <>
              <Field label="List A — title">
                <Text
                  value={form.listATitle}
                  onChange={(v) => setForm({ ...form, listATitle: v })}
                />
              </Field>
              <Field label="List A — issuing authority">
                <Text
                  value={form.listAIssuingAuthority}
                  onChange={(v) =>
                    setForm({ ...form, listAIssuingAuthority: v })
                  }
                />
              </Field>
              <Field label="List A — document #">
                <Text
                  value={form.listADocumentNumber}
                  onChange={(v) =>
                    setForm({ ...form, listADocumentNumber: v })
                  }
                />
              </Field>
              <Field label="List A — expiration">
                <DateF
                  value={form.listAExpirationDate}
                  onChange={(v) =>
                    setForm({ ...form, listAExpirationDate: v })
                  }
                />
              </Field>
            </>
          ) : (
            <>
              <Field label="List B — title">
                <Text
                  value={form.listBTitle}
                  onChange={(v) => setForm({ ...form, listBTitle: v })}
                />
              </Field>
              <Field label="List B — issuing authority">
                <Text
                  value={form.listBIssuingAuthority}
                  onChange={(v) =>
                    setForm({ ...form, listBIssuingAuthority: v })
                  }
                />
              </Field>
              <Field label="List B — document #">
                <Text
                  value={form.listBDocumentNumber}
                  onChange={(v) =>
                    setForm({ ...form, listBDocumentNumber: v })
                  }
                />
              </Field>
              <Field label="List B — expiration">
                <DateF
                  value={form.listBExpirationDate}
                  onChange={(v) =>
                    setForm({ ...form, listBExpirationDate: v })
                  }
                />
              </Field>
              <Field label="List C — title">
                <Text
                  value={form.listCTitle}
                  onChange={(v) => setForm({ ...form, listCTitle: v })}
                />
              </Field>
              <Field label="List C — issuing authority">
                <Text
                  value={form.listCIssuingAuthority}
                  onChange={(v) =>
                    setForm({ ...form, listCIssuingAuthority: v })
                  }
                />
              </Field>
              <Field label="List C — document #">
                <Text
                  value={form.listCDocumentNumber}
                  onChange={(v) =>
                    setForm({ ...form, listCDocumentNumber: v })
                  }
                />
              </Field>
              <span />
            </>
          )}

          <Field label="Employer name">
            <Text
              value={form.employerName}
              onChange={(v) => setForm({ ...form, employerName: v })}
            />
          </Field>
          <Field label="Employer title">
            <Text
              value={form.employerTitle}
              onChange={(v) => setForm({ ...form, employerTitle: v })}
            />
          </Field>
          <Field label="Business organisation name">
            <Text
              value={form.businessOrganizationName}
              onChange={(v) =>
                setForm({ ...form, businessOrganizationName: v })
              }
            />
          </Field>
          <Field label="Business address">
            <Text
              value={form.businessAddress}
              onChange={(v) => setForm({ ...form, businessAddress: v })}
            />
          </Field>

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
            {saving ? 'Saving…' : 'Sign Section 2'}
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

function Text({
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
