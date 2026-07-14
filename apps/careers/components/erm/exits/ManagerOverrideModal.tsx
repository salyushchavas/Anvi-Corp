'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import type {
  ErmExitDetail,
  ReasonCodeGroup,
  ReasonCodeOption,
} from './types';

type Props = {
  exitRecordId: string;
  pendingCount: number;
  onClose: () => void;
  onDone: (updated: ErmExitDetail) => void;
};

export default function ManagerOverrideModal({
  exitRecordId,
  pendingCount,
  onClose,
  onDone,
}: Props) {
  const [reasonCode, setReasonCode] = useState('');
  const [reasonText, setReasonText] = useState('');
  const [reasons, setReasons] = useState<ReasonCodeOption[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<ReasonCodeGroup[]>('/api/v1/erm/exits/reason-codes')
      .then((res) => {
        const grp = res.data.find((g) => g.family === 'EXIT_OVERRIDE');
        setReasons(grp?.options ?? []);
      })
      .catch(() => setReasons([]));
  }, []);

  const canSubmit =
    reasonCode.length > 0 && reasonText.trim().length >= 30 && !submitting;

  async function submit() {
    setSubmitting(true);
    setErr(null);
    try {
      const res = await api.post<ErmExitDetail>(
        `/api/v1/erm/exits/${exitRecordId}/manager-override`,
        { reasonCode, reasonText: reasonText.trim() },
      );
      onDone(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Override failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">
            Manager override
          </h3>
          <p className="text-xs text-amber-700">
            Marking <strong>{pendingCount}</strong> PENDING checklist item
            {pendingCount === 1 ? '' : 's'} as WAIVED. This can't be undone
            via the ERM UI.
          </p>
        </div>
        <div className="space-y-3 px-5 py-4">
          <label className="block text-xs font-medium text-slate-700">
            Reason code <span className="text-red-500">*</span>
            <select
              value={reasonCode}
              onChange={(e) => setReasonCode(e.target.value)}
              className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            >
              <option value="">Select a reason…</option>
              {reasons.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.label}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-xs font-medium text-slate-700">
            Reason detail (≥30 chars) <span className="text-red-500">*</span>
            <textarea
              rows={3}
              value={reasonText}
              onChange={(e) => setReasonText(e.target.value)}
              className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
            <span className="mt-0.5 block text-[11px] text-slate-500">
              {reasonText.trim().length}/30 min
            </span>
          </label>
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
            disabled={!canSubmit}
            onClick={() => void submit()}
            className="rounded-md bg-red-600 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {submitting ? 'Saving…' : 'Confirm override'}
          </button>
        </div>
      </div>
    </div>
  );
}
