'use client';

import { useState } from 'react';
import api from '@/lib/api';

interface Props {
  offerId?: string;
  lifecycleId?: string;
  currentDate: string | null;
  open: boolean;
  onClose: () => void;
  onApplied: () => void;
}

export default function UpdateStartDateModal({
  offerId,
  lifecycleId,
  currentDate,
  open,
  onClose,
  onApplied,
}: Props) {
  const [newDate, setNewDate] = useState(currentDate ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (!open) return null;

  async function submit() {
    setErr(null);
    if (!newDate) { setErr('Pick a date.'); return; }
    setSubmitting(true);
    try {
      if (offerId) {
        await api.post(`/api/v1/erm/offers/${offerId}/update-start-date`, { newDate });
      } else if (lifecycleId) {
        await api.post(`/api/v1/erm/new-hire/${lifecycleId}/update-start-date`, { newDate });
      } else {
        throw new Error('Missing offerId or lifecycleId');
      }
      onApplied();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Update failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h2 className="text-lg font-semibold text-slate-900">Update tentative start date</h2>
        <p className="mt-2 text-xs text-slate-500">
          Applicant + reporting structure will be notified.
        </p>
        <div className="mt-4">
          <label className="text-sm font-medium text-slate-800">New date</label>
          <input
            type="date"
            value={newDate}
            onChange={(e) => setNewDate(e.target.value)}
            className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
          />
        </div>
        {err && (
          <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}
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
            {submitting ? 'Updating…' : 'Update'}
          </button>
        </div>
      </div>
    </div>
  );
}
