'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';
import type {
  ErmExitDetail,
  ExitType,
  InitiateExitRequest,
  ReasonCodeGroup,
  ReasonCodeOption,
} from './types';

type Props = {
  defaultLifecycleId: string;
  defaultExitType?: ExitType;
  onClose: () => void;
  onCreated?: (detail: ErmExitDetail) => void;
};

const TYPES: ExitType[] = ['COMPLETED', 'RESIGNED', 'TERMINATED', 'EXTENDED'];
const FT_STATUSES = ['ALL_APPROVED', 'PENDING', 'WAIVED'];

export default function InitiateExitModal({
  defaultLifecycleId,
  defaultExitType,
  onClose,
  onCreated,
}: Props) {
  const router = useRouter();
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState<InitiateExitRequest>({
    internLifecycleId: defaultLifecycleId,
    exitType: defaultExitType ?? 'COMPLETED',
    exitDate: today,
    lastWorkingDay: today,
    rehireEligible: true,
    finalTimesheetStatus: 'PENDING',
  });
  const [reasons, setReasons] = useState<ReasonCodeOption[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<ReasonCodeGroup[]>('/api/v1/erm/exits/reason-codes')
      .then((res) => {
        const exitGroup = res.data.find((g) => g.family === 'EXIT');
        setReasons(exitGroup?.options ?? []);
      })
      .catch(() => setReasons([]));
  }, []);

  const reasonRequired =
    form.exitType === 'RESIGNED' || form.exitType === 'TERMINATED';
  const selectedReason = reasons.find((r) => r.code === form.reasonCode);
  const needsFreeText = !!selectedReason?.requiresFreeText || reasonRequired;
  const reasonValid = !reasonRequired || (form.reasonCode?.length ?? 0) > 0;
  const freeTextValid =
    !needsFreeText || (form.reasonText?.trim().length ?? 0) >= 30;
  const summaryValid =
    !form.internVisibleSummary || form.internVisibleSummary.length <= 500;
  const canSubmit =
    reasonValid && freeTextValid && summaryValid && !submitting;

  async function submit() {
    setSubmitting(true);
    setErr(null);
    try {
      const res = await api.post<ErmExitDetail>(
        '/api/v1/erm/exits/initiate',
        form,
      );
      if (onCreated) onCreated(res.data);
      router.push(`/careers/erm/exits/${res.data.exitRecordId}`);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Initiate failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-xl rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">
            Initiate exit
          </h3>
          <p className="text-xs text-slate-500">
            Lifecycle <code>{defaultLifecycleId.slice(0, 8)}</code>
          </p>
        </div>
        <div className="grid grid-cols-1 gap-3 px-5 py-4 sm:grid-cols-2">
          <Field label="Exit type">
            <div className="flex flex-wrap gap-2">
              {TYPES.map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setForm({ ...form, exitType: t })}
                  className={
                    'rounded-md border px-3 py-1.5 text-xs font-medium ' +
                    (form.exitType === t
                      ? 'border-brand-700 bg-brand-700 text-white'
                      : 'border-slate-200 text-slate-700')
                  }
                >
                  {t}
                </button>
              ))}
            </div>
          </Field>
          <Field label="Final timesheet">
            <select
              value={form.finalTimesheetStatus ?? 'PENDING'}
              onChange={(e) =>
                setForm({ ...form, finalTimesheetStatus: e.target.value })
              }
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            >
              {FT_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Exit date (record-keeping)">
            <input
              type="date"
              value={form.exitDate}
              onChange={(e) => setForm({ ...form, exitDate: e.target.value })}
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
          </Field>
          <Field label="Last working day">
            <input
              type="date"
              value={form.lastWorkingDay ?? ''}
              onChange={(e) =>
                setForm({ ...form, lastWorkingDay: e.target.value || undefined })
              }
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
          </Field>
          <Field label="Rehire eligible">
            <input
              type="checkbox"
              checked={!!form.rehireEligible}
              onChange={(e) =>
                setForm({ ...form, rehireEligible: e.target.checked })
              }
            />
          </Field>
          <Field
            label={
              'Reason code' + (reasonRequired ? ' (required)' : '')
            }
          >
            <select
              value={form.reasonCode ?? ''}
              onChange={(e) =>
                setForm({ ...form, reasonCode: e.target.value || undefined })
              }
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            >
              <option value="">—</option>
              {reasons.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.label}
                </option>
              ))}
            </select>
          </Field>
          {needsFreeText && (
            <div className="sm:col-span-2">
              <Field
                label={
                  'Reason detail (≥30 chars' +
                  (reasonRequired ? ', required' : '') +
                  ')'
                }
              >
                <textarea
                  rows={2}
                  value={form.reasonText ?? ''}
                  onChange={(e) =>
                    setForm({ ...form, reasonText: e.target.value })
                  }
                  className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
                />
                <span className="mt-0.5 block text-[11px] text-slate-500">
                  {form.reasonText?.trim().length ?? 0}/30 min
                </span>
              </Field>
            </div>
          )}
          <div className="sm:col-span-2">
            <Field label="Intern-visible summary (max 500)">
              <textarea
                rows={3}
                value={form.internVisibleSummary ?? ''}
                onChange={(e) =>
                  setForm({
                    ...form,
                    internVisibleSummary: e.target.value,
                  })
                }
                className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
              />
              <span className="mt-0.5 block text-[11px] text-slate-500">
                {(form.internVisibleSummary ?? '').length}/500
              </span>
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
            disabled={!canSubmit}
            onClick={() => void submit()}
            className="rounded-md bg-red-600 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {submitting ? 'Initiating…' : 'Initiate exit'}
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
