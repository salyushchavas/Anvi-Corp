'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { AlertTriangle, CheckCircle2, PlayCircle, RotateCcw, X } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Manager ⟶ Recording Approvals. FIFO queue of session recordings the
 * evaluator uploaded that need a manager sign-off before the evaluator
 * (or the evaluation compose page's inline player) can see them. Each
 * row inline-plays the recording via the existing
 * {@code /api/v1/evaluation-recordings/{id}/download-url} endpoint —
 * MANAGER role is already whitelisted there.
 */
interface PendingRow {
  evaluationId: string;
  recordingDocumentId: string;
  monthYear: string;
  evaluationType: string;
  scope: string | null;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  projectId: string | null;
  projectTitle: string | null;
  evaluatorId: string;
  evaluatorName: string | null;
  fileName: string;
  fileSizeBytes: number | null;
  uploadedAt: string;
  approvalStatus: string;
  hoursWaiting: number;
}

interface PendingListResponse {
  items: PendingRow[];
  totalCount: number;
}

export default function RecordingApprovalsPage() {
  const [data, setData] = useState<PendingListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [openRow, setOpenRow] = useState<PendingRow | null>(null);
  const [revisionFor, setRevisionFor] = useState<PendingRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<PendingListResponse>('/api/v1/manager/recording-approvals');
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function approve(row: PendingRow) {
    try {
      await api.post(`/api/v1/manager/recording-approvals/${row.evaluationId}/approve`);
      setOpenRow(null);
      void load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to approve');
    }
  }

  return (
    <div className="mx-auto max-w-6xl space-y-5 p-6">
      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-brand-700">Manager</p>
        <h1 className="mt-1 text-2xl font-semibold text-slate-900">Recording Approvals</h1>
        <p className="mt-1 text-sm text-slate-600">
          Session recordings uploaded by evaluators, waiting on your review. Watch inline,
          then approve (evaluator sees it) or request revision (evaluator gets your notes and
          can re-upload).
        </p>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      <p className="text-xs text-slate-500">
        {data ? `${data.totalCount} awaiting approval` : ''}
      </p>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : !data || data.items.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            Nothing waiting on your approval.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Project / Scope</th>
                <th className="px-3 py-2">Month</th>
                <th className="px-3 py-2">Evaluator</th>
                <th className="px-3 py-2">Uploaded</th>
                <th className="px-3 py-2">Waiting</th>
                <th className="px-3 py-2 text-right"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((r) => {
                const urgent = r.hoursWaiting > 48;
                return (
                  <tr key={r.evaluationId} className="hover:bg-slate-50">
                    <td className="px-3 py-2">
                      <p className="text-sm font-medium text-slate-900">{r.internName ?? '—'}</p>
                      <p className="text-[11px] text-slate-500">{r.employeeId ?? '—'}</p>
                    </td>
                    <td className="px-3 py-2 text-xs">
                      <p className="text-slate-800">{r.projectTitle ?? '—'}</p>
                      <p className="text-[10px] text-slate-500">
                        {r.scope ? r.scope.replace('_', '+') : r.evaluationType}
                      </p>
                    </td>
                    <td className="px-3 py-2 text-xs text-slate-700">{r.monthYear}</td>
                    <td className="px-3 py-2 text-xs text-slate-700">{r.evaluatorName ?? '—'}</td>
                    <td className="px-3 py-2 text-xs text-slate-700">
                      {new Date(r.uploadedAt).toLocaleString()}
                    </td>
                    <td className="px-3 py-2 text-xs">
                      <span className={
                        'inline-flex items-center gap-1 rounded px-2 py-0.5 font-semibold '
                        + (urgent ? 'bg-red-100 text-red-800' : 'text-slate-700')
                      }>
                        {urgent && <AlertTriangle className="h-3 w-3" />}
                        {Math.round(r.hoursWaiting)}h
                      </span>
                    </td>
                    <td className="px-3 py-2 text-right">
                      <button
                        type="button"
                        onClick={() => setOpenRow(r)}
                        className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
                      >
                        <PlayCircle className="h-3 w-3" />
                        Review
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {openRow && (
        <ReviewModal
          row={openRow}
          onClose={() => setOpenRow(null)}
          onApprove={() => approve(openRow)}
          onRequestRevision={() => { setOpenRow(null); setRevisionFor(openRow); }}
        />
      )}

      {revisionFor && (
        <RevisionModal
          row={revisionFor}
          onClose={() => setRevisionFor(null)}
          onSubmitted={() => { setRevisionFor(null); void load(); }}
          onError={setErr}
        />
      )}
    </div>
  );
}

function ReviewModal({ row, onClose, onApprove, onRequestRevision }: {
  row: PendingRow;
  onClose: () => void;
  onApprove: () => void | Promise<void>;
  onRequestRevision: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const res = await api.get<{ downloadUrl: string }>(
          `/api/v1/evaluation-recordings/${row.evaluationId}/download-url`,
        );
        if (!cancelled) setUrl(res.data.downloadUrl);
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        if (!cancelled) setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load video');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [row.evaluationId]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="flex max-h-[92vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              {row.internName ?? 'Intern'} · {row.projectTitle ?? row.evaluationType}
            </h3>
            <p className="text-xs text-slate-500">
              {row.monthYear}
              {row.scope && <> · scope {row.scope.replace('_', '+')}</>}
              · uploaded by {row.evaluatorName ?? 'evaluator'}
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex-1 space-y-3 overflow-y-auto p-5">
          {loading && <div className="h-56 animate-pulse rounded bg-slate-100" />}
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
          {url && (
            // eslint-disable-next-line jsx-a11y/media-has-caption
            <video controls src={url} className="w-full rounded-md bg-black" />
          )}
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">
            Close
          </button>
          <button type="button" onClick={onRequestRevision}
            className="inline-flex items-center gap-1 rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-sm font-semibold text-amber-900 hover:bg-amber-100">
            <RotateCcw className="h-3.5 w-3.5" />
            Request revision
          </button>
          <button type="button" onClick={() => void onApprove()}
            className="inline-flex items-center gap-1 rounded-md bg-emerald-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-emerald-800">
            <CheckCircle2 className="h-3.5 w-3.5" />
            Approve
          </button>
        </div>
      </div>
    </div>
  );
}

function RevisionModal({ row, onClose, onSubmitted, onError }: {
  row: PendingRow;
  onClose: () => void;
  onSubmitted: () => void;
  onError: (msg: string) => void;
}) {
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [localErr, setLocalErr] = useState<string | null>(null);

  async function submit() {
    if (notes.trim().length < 10) {
      setLocalErr('Notes must be at least 10 characters — tell the evaluator what to fix.');
      return;
    }
    setSubmitting(true);
    setLocalErr(null);
    try {
      await api.post(
        `/api/v1/manager/recording-approvals/${row.evaluationId}/request-revision`,
        { notes: notes.trim() },
      );
      onSubmitted();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      const msg = ax.response?.data?.error ?? ax.message ?? 'Failed to submit';
      setLocalErr(msg);
      onError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <h3 className="text-base font-semibold text-slate-900">
            Request revision
          </h3>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="space-y-3 p-5 text-sm">
          <p className="text-xs text-slate-500">
            The evaluator ({row.evaluatorName ?? '—'}) will see these notes verbatim and can
            re-upload a corrected recording. Be specific — "voice unclear at 04:20", "screen not
            shared for the DB question", etc.
          </p>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">
              Revision notes (min 10 chars — {notes.length})
            </span>
            <textarea value={notes} onChange={(e) => setNotes(e.target.value)}
              rows={5} maxLength={4000}
              className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
          </label>
          {localErr && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {localErr}
            </p>
          )}
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
          <button type="button" onClick={submit} disabled={submitting}
            className="rounded-md bg-amber-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-amber-800 disabled:bg-slate-300">
            {submitting ? 'Sending…' : 'Send back for revision'}
          </button>
        </div>
      </div>
    </div>
  );
}
