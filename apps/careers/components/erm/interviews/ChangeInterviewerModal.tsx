'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import type { InterviewDetail, InterviewerView } from './types';

interface Props {
  interview: InterviewDetail;
  open: boolean;
  onClose: () => void;
  onApplied: () => void;
}

export default function ChangeInterviewerModal({
  interview,
  open,
  onClose,
  onApplied,
}: Props) {
  const [interviewers, setInterviewers] = useState<InterviewerView[]>([]);
  const [pickId, setPickId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setPickId('');
    setErr(null);
    void (async () => {
      try {
        const res = await api.get<InterviewerView[]>(
          '/api/v1/erm/interviews/eligible-interviewers',
        );
        const filtered = (res.data ?? []).filter(
          (i) => i.userId !== interview.interviewer?.userId,
        );
        setInterviewers(filtered);
      } catch {
        setInterviewers([]);
      }
    })();
  }, [open, interview.interviewer?.userId]);

  if (!open) return null;

  async function submit() {
    setErr(null);
    if (!pickId) {
      setErr('Pick a new interviewer.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post(
        `/api/v1/erm/interviews/${interview.id}/change-interviewer`,
        { interviewerId: pickId },
      );
      onApplied();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Failed to change interviewer'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
        <h2 className="text-lg font-semibold text-slate-900">Change interviewer</h2>
        <p className="mt-2 text-xs text-slate-500">
          The Zoom meeting will be deleted and recreated with the new host, and
          the applicant will be notified.
        </p>

        <div className="mt-4">
          <label className="text-sm font-medium text-slate-800">New interviewer</label>
          <select
            value={pickId}
            onChange={(e) => setPickId(e.target.value)}
            className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
          >
            <option value="">Select an interviewer…</option>
            {interviewers.map((i) => (
              <option key={i.userId} value={i.userId}>
                {i.fullName} · {i.role}{i.hasZoomEmail ? '' : ' (no Zoom email)'}
              </option>
            ))}
          </select>
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
            {submitting ? 'Updating…' : 'Change interviewer'}
          </button>
        </div>
      </div>
    </div>
  );
}
