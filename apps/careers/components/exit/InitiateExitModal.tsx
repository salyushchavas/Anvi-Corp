'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';

interface Props {
  internLifecycleId: string;
  internName?: string | null;
  open: boolean;
  onClose: () => void;
}

const EXIT_TYPES = ['COMPLETED', 'RESIGNED', 'TERMINATED', 'EXTENDED'] as const;

export default function InitiateExitModal({ internLifecycleId, internName, open, onClose }: Props) {
  const router = useRouter();
  const [exitType, setExitType] = useState<typeof EXIT_TYPES[number]>('COMPLETED');
  const [exitDate, setExitDate] = useState<string>(new Date().toISOString().slice(0, 10));
  const [exitReason, setExitReason] = useState('');
  const [internVisibleSummary, setInternVisibleSummary] = useState('');
  const [rehireEligible, setRehireEligible] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (!open) return null;
  const reasonRequired = exitType === 'RESIGNED' || exitType === 'TERMINATED';

  async function submit() {
    setErr(null);
    if (!exitDate) {
      setErr('Exit date is required');
      return;
    }
    if (reasonRequired && exitReason.trim().length < 30) {
      setErr('Exit reason must be at least 30 characters for RESIGNED or TERMINATED');
      return;
    }
    if (internVisibleSummary.length > 500) {
      setErr('Intern-visible summary cannot exceed 500 characters');
      return;
    }
    setSubmitting(true);
    try {
      const res = await api.post<{ id: string }>('/api/v1/exit/records', {
        internLifecycleId,
        exitType,
        exitDate,
        exitReason: exitReason.trim() || null,
        internVisibleSummary: internVisibleSummary.trim() || null,
        rehireEligible,
      });
      router.push(`/careers/erm/exits/${res.data.id}`);
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(ax.response?.data?.error
        ?? (e instanceof Error ? e.message : 'Failed to initiate exit'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">
            Initiate exit{internName ? ` — ${internName}` : ''}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div className="mt-4 space-y-4">
          <div>
            <label className="text-sm font-medium text-slate-800">Exit type</label>
            <div className="mt-2 grid grid-cols-2 gap-2">
              {EXIT_TYPES.map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setExitType(t)}
                  className={
                    'rounded-md border px-3 py-2 text-sm font-medium transition-colors ' +
                    (exitType === t
                      ? 'border-brand-600 bg-brand-600 text-white'
                      : 'border-slate-200 text-slate-700 hover:bg-slate-50')
                  }
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="text-sm font-medium text-slate-800" htmlFor="exit-date">
              Exit date
            </label>
            <input
              id="exit-date"
              type="date"
              value={exitDate}
              onChange={(e) => setExitDate(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>

          {reasonRequired && (
            <div>
              <label className="text-sm font-medium text-slate-800" htmlFor="exit-reason">
                Exit reason <span className="text-xs text-slate-500">(min 30 chars)</span>
              </label>
              <textarea
                id="exit-reason"
                value={exitReason}
                onChange={(e) => setExitReason(e.target.value)}
                rows={3}
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
            </div>
          )}

          <div>
            <label className="text-sm font-medium text-slate-800" htmlFor="visible-summary">
              Intern-visible summary <span className="text-xs text-slate-500">(max 500)</span>
            </label>
            <textarea
              id="visible-summary"
              value={internVisibleSummary}
              onChange={(e) => setInternVisibleSummary(e.target.value)}
              rows={3}
              maxLength={500}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              placeholder="Shown on the intern's Home screen."
            />
            <div className="mt-1 text-right text-[11px] text-slate-500">
              {internVisibleSummary.length} / 500
            </div>
          </div>

          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={rehireEligible}
              onChange={(e) => setRehireEligible(e.target.checked)}
            />
            Rehire eligible
          </label>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              {err}
            </p>
          )}
        </div>

        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Initiating…' : 'Initiate exit'}
          </button>
        </div>
      </div>
    </div>
  );
}
