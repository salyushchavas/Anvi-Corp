'use client';

import { useState } from 'react';
import api from '@/lib/api';
import type { ExceptionDetail } from './types';

type Props = {
  exceptionId: string;
  defaultAssigneeUserId: string | null;
  onClose: () => void;
  onDone: (updated: ExceptionDetail) => void;
};

export default function AssignExceptionModal({
  exceptionId,
  defaultAssigneeUserId,
  onClose,
  onDone,
}: Props) {
  const [assigneeUserId, setAssigneeUserId] = useState(
    defaultAssigneeUserId ?? '',
  );
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSubmitting(true);
    setErr(null);
    try {
      const res = await api.post<ExceptionDetail>(
        `/api/v1/erm/escalations/${exceptionId}/assign`,
        { assigneeUserId },
      );
      onDone(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Assign failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">
            Assign exception
          </h3>
        </div>
        <div className="px-5 py-4">
          <label className="block text-xs font-medium text-slate-700">
            Assignee user ID <span className="text-red-500">*</span>
            <input
              value={assigneeUserId}
              onChange={(e) => setAssigneeUserId(e.target.value)}
              placeholder="ERM / MANAGER / SUPER_ADMIN user UUID"
              className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
            <span className="mt-0.5 block text-[11px] text-slate-500">
              Defaults to current ERM for self-assignment.
            </span>
          </label>
          {err && (
            <p className="mt-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
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
            disabled={submitting || !assigneeUserId.trim()}
            onClick={() => void submit()}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {submitting ? 'Saving…' : 'Assign'}
          </button>
        </div>
      </div>
    </div>
  );
}
