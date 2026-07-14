'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import type {
  ExceptionDetail,
  ReasonCodeGroup,
  ReasonCodeOption,
} from './types';

type Props = {
  exceptionId: string;
  mode: 'resolve' | 'dismiss';
  onClose: () => void;
  onDone: (updated: ExceptionDetail) => void;
};

export default function ResolveExceptionModal({
  exceptionId,
  mode,
  onClose,
  onDone,
}: Props) {
  const [reasons, setReasons] = useState<ReasonCodeOption[]>([]);
  const [reasonCode, setReasonCode] = useState('');
  const [reasonText, setReasonText] = useState('');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<ReasonCodeGroup[]>('/api/v1/erm/escalations/reason-codes')
      .then((res) => {
        const family = mode === 'resolve' ? 'EXCEPTION_RESOLVE' : 'EXCEPTION_DISMISS';
        const grp = res.data.find((g) => g.family === family);
        setReasons(grp?.options ?? []);
      })
      .catch(() => setReasons([]));
  }, [mode]);

  const selected = reasons.find((r) => r.code === reasonCode);
  const needsFreeText = !!selected?.requiresFreeText;
  const reasonValid = reasonCode.length > 0;
  const freeTextValid = !needsFreeText || reasonText.trim().length >= 10;
  const noteValid = note.trim().length >= 10;
  const canSubmit = reasonValid && freeTextValid && noteValid && !submitting;

  async function submit() {
    setSubmitting(true);
    setErr(null);
    try {
      const path =
        mode === 'resolve'
          ? `/api/v1/erm/escalations/${exceptionId}/resolve`
          : `/api/v1/erm/escalations/${exceptionId}/dismiss`;
      const body =
        mode === 'resolve'
          ? {
              reasonCode,
              reasonText: reasonText.trim() || undefined,
              resolutionNote: note.trim(),
            }
          : {
              reasonCode,
              reasonText: reasonText.trim() || undefined,
              dismissalNote: note.trim(),
            };
      const res = await api.post<ExceptionDetail>(path, body);
      onDone(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Action failed');
    } finally {
      setSubmitting(false);
    }
  }

  const title = mode === 'resolve' ? 'Resolve exception' : 'Dismiss exception';
  const cta = mode === 'resolve' ? 'Resolve' : 'Dismiss';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
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
          {needsFreeText && (
            <label className="block text-xs font-medium text-slate-700">
              Reason detail <span className="text-red-500">*</span>
              <textarea
                value={reasonText}
                onChange={(e) => setReasonText(e.target.value)}
                rows={2}
                placeholder="At least 10 characters"
                className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
              />
            </label>
          )}
          <label className="block text-xs font-medium text-slate-700">
            {mode === 'resolve' ? 'Resolution note' : 'Dismissal note'}{' '}
            <span className="text-red-500">*</span>
            <textarea
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={3}
              placeholder="At least 10 characters — ERM-only"
              className="mt-1 block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
            <span className="mt-0.5 block text-[11px] text-slate-500">
              {note.trim().length}/10 min
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
            className="rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {submitting ? 'Saving…' : cta}
          </button>
        </div>
      </div>
    </div>
  );
}
