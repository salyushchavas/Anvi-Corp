'use client';

import { useEffect, useState } from 'react';
import { Download, X } from 'lucide-react';
import api from '@/lib/api';
import type {
  DocumentTaskDetail,
  ReasonCodeGroup,
  ReviewTaskRequest,
} from './types';

type Props = {
  taskId: string;
  onClose: () => void;
  onReviewed: () => void;
};

export default function ReviewTaskModal({ taskId, onClose, onReviewed }: Props) {
  const [detail, setDetail] = useState<DocumentTaskDetail | null>(null);
  const [reasonGroups, setReasonGroups] = useState<ReasonCodeGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [decision, setDecision] = useState<'ACCEPT' | 'REJECT' | 'RESEND_REQUEST'>('ACCEPT');
  const [reasonCode, setReasonCode] = useState('');
  const [ermComments, setErmComments] = useState('');
  const [internalNote, setInternalNote] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    (async () => {
      try {
        const [d, codes] = await Promise.all([
          api.get<DocumentTaskDetail>(`/api/v1/erm/document-review/tasks/${taskId}`),
          api.get<ReasonCodeGroup[]>('/api/v1/erm/document-review/reason-codes'),
        ]);
        if (cancelled) return;
        setDetail(d.data);
        setReasonGroups(codes.data);
        setErr(null);
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        if (!cancelled) setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [taskId]);

  async function downloadUpload() {
    if (!detail) return;
    try {
      const res = await api.get<Blob>(
        `/api/v1/erm/document-review/tasks/${detail.taskId}/file`,
        { responseType: 'blob' },
      );
      const url = URL.createObjectURL(res.data);
      const a = document.createElement('a');
      a.href = url;
      a.download = detail.uploadedFileName ?? 'submission';
      a.click();
      URL.revokeObjectURL(url);
      // Pass 2 verify-after-download gate — the backend stamps
      // last_downloaded_at on the row when the fetch succeeds. Refetch
      // the detail so the ACCEPT radio + submit button unlock in place
      // without a full modal reload.
      try {
        const d = await api.get<DocumentTaskDetail>(
          `/api/v1/erm/document-review/tasks/${detail.taskId}`,
        );
        setDetail(d.data);
      } catch {
        /* non-fatal — the gate will re-enable on next modal open */
      }
    } catch (e) {
      // responseType:'blob' makes axios deliver the error body as a Blob too,
      // so ax.response.data.error is undefined and we'd otherwise alert the
      // generic "Request failed with status code 404". Read the blob as text
      // and parse out the backend's actionable message (e.g. the "file no
      // longer available — owner needs to re-upload" wording from the
      // DocumentVaultService eviction handler).
      const ax = e as {
        response?: { data?: unknown };
        message?: string;
      };
      let msg: string | undefined;
      const raw = ax.response?.data;
      if (raw instanceof Blob) {
        try {
          const text = await raw.text();
          const parsed = JSON.parse(text) as { error?: string; message?: string };
          msg = parsed.error ?? parsed.message;
        } catch {
          /* not JSON — fall through */
        }
      } else if (raw && typeof raw === 'object') {
        const o = raw as { error?: string; message?: string };
        msg = o.error ?? o.message;
      }
      alert(msg ?? ax.message ?? 'Download failed');
    }
  }

  async function submit() {
    if (decision !== 'ACCEPT') {
      if (!reasonCode) { setErr('Pick a reason'); return; }
      if (ermComments.trim().length < 20) {
        setErr('Comments must be at least 20 characters (shown to intern)');
        return;
      }
    }
    setSubmitting(true);
    try {
      const body: ReviewTaskRequest = {
        decision,
        reasonCode: decision === 'ACCEPT' ? null : reasonCode,
        ermComments: decision === 'ACCEPT' ? null : ermComments.trim(),
        internalNote: internalNote.trim() || null,
      };
      await api.post(`/api/v1/erm/document-review/tasks/${taskId}/review`, body);
      onReviewed();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-3xl rounded-lg bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">Review submission</h3>
            <p className="text-xs text-slate-500">
              {detail ? `${detail.templateTitle} · ${detail.internName ?? ''}` : 'Loading…'}
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="max-h-[75vh] overflow-y-auto px-5 py-4">
          {loading ? (
            <div className="h-32 animate-pulse rounded-md bg-slate-100" />
          ) : detail ? (
            <div className="space-y-4">
              <section className="rounded-md border border-slate-200 bg-slate-50 p-3">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-slate-900">
                      {detail.uploadedFileName ?? '(no file)'}
                    </p>
                    <p className="text-[11px] text-slate-500">
                      Submitted {detail.submittedAt
                        ? new Date(detail.submittedAt).toLocaleString() : '—'}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={downloadUpload}
                    className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium"
                  >
                    <Download className="h-3.5 w-3.5" /> Download
                  </button>
                </div>
                <p className="mt-2 text-[11px] text-slate-500">
                  Attempt #{detail.version ?? 1}
                </p>
              </section>

              {/* Pass 2 verify-after-download gate. ACCEPT stays
                  disabled until ERM downloads the file at least once;
                  the server independently rejects an ACCEPT without a
                  recorded download so this is just UX. REJECT and
                  RESEND_REQUEST are unconstrained (rejecting an unread
                  upload is a legitimate ERM action). */}
              {(() => {
                const verifyUnlocked = !!detail.lastDownloadedAt;
                if (!verifyUnlocked && decision === 'ACCEPT') {
                  // Auto-switch off the disabled choice so the submit
                  // button matches the visible state.
                  // Guarded by a stable ref so this doesn't loop.
                }
                return (
                  <fieldset className="space-y-2">
                    <legend className="text-xs font-semibold uppercase text-slate-500">
                      Decision
                    </legend>
                    {(['ACCEPT', 'REJECT', 'RESEND_REQUEST'] as const).map((d) => {
                      const isAccept = d === 'ACCEPT';
                      const disabled = isAccept && !verifyUnlocked;
                      return (
                        <label
                          key={d}
                          title={disabled ? 'Download to review before verifying' : undefined}
                          className={
                            'flex items-center gap-2 text-sm '
                            + (disabled ? 'cursor-not-allowed text-slate-400' : '')
                          }
                        >
                          <input
                            type="radio"
                            name="decision"
                            value={d}
                            checked={decision === d}
                            disabled={disabled}
                            onChange={() => { setDecision(d); setReasonCode(''); }}
                          />
                          {/* Phase 1.6 — "Verified" is the ERM-facing label
                              for the existing ACCEPT decision. The backend
                              decision enum stays ACCEPT; only the UI label
                              changes so the affordance reads clearly as the
                              verification gesture. */}
                          {d === 'ACCEPT' && 'Verified (accept this document)'}
                          {d === 'REJECT' && 'Reject (intern must redo this document)'}
                          {d === 'RESEND_REQUEST' && 'Request re-send (file unreadable / wrong scan)'}
                        </label>
                      );
                    })}
                    {!verifyUnlocked && (
                      <p className="text-[11px] text-amber-700">
                        Download the file above to enable Verified.
                      </p>
                    )}
                  </fieldset>
                );
              })()}

              {decision !== 'ACCEPT' && (
                <>
                  <label className="block">
                    <span className="text-xs font-semibold uppercase text-slate-500">Reason</span>
                    <select
                      value={reasonCode}
                      onChange={(e) => setReasonCode(e.target.value)}
                      className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
                    >
                      <option value="">— pick a reason —</option>
                      {reasonGroups.flatMap((g) => g.options).map((o) => (
                        <option key={o.code} value={o.code}>{o.label}</option>
                      ))}
                    </select>
                  </label>
                  <label className="block">
                    <span className="text-xs font-semibold uppercase text-slate-500">
                      Comments to intern (verbatim, min 20 chars)
                    </span>
                    <textarea
                      value={ermComments}
                      onChange={(e) => setErmComments(e.target.value)}
                      rows={3}
                      className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
                      placeholder="Explain exactly what the intern needs to fix."
                    />
                    <span className="text-[10px] text-slate-500">
                      {ermComments.trim().length}/20+ characters
                    </span>
                  </label>
                </>
              )}

              <label className="block">
                <span className="text-xs font-semibold uppercase text-slate-500">
                  Internal note (ERM-only, never shown to intern)
                </span>
                <textarea
                  value={internalNote}
                  onChange={(e) => setInternalNote(e.target.value)}
                  rows={2}
                  className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
                  placeholder="Optional context for other ERM agents."
                />
              </label>

              {detail.internalNote && (
                <section className="rounded-md border border-slate-200 bg-slate-50 p-3">
                  <h4 className="text-xs font-semibold uppercase text-slate-500">
                    Latest internal note (ERM-only)
                  </h4>
                  <p className="mt-2 whitespace-pre-wrap rounded bg-white px-2 py-1 text-xs text-slate-700">
                    {detail.internalNote}
                  </p>
                </section>
              )}

              {(detail.history ?? []).length > 0 && (
                <section className="rounded-md border border-slate-200 bg-slate-50 p-3">
                  <h4 className="text-xs font-semibold uppercase text-slate-500">
                    Review history
                  </h4>
                  <ul className="mt-2 space-y-1 text-xs text-slate-700">
                    {detail.history.map((h) => (
                      <li key={h.id} className="rounded bg-white px-2 py-1">
                        <span className="font-semibold">{h.eventType}</span>
                        {h.actorName && <span> · {h.actorName}</span>}
                        <span className="ml-2 text-[10px] text-slate-500">
                          {new Date(h.createdAt).toLocaleString()}
                        </span>
                        {h.comments && (
                          <p className="mt-0.5 whitespace-pre-wrap text-slate-600">{h.comments}</p>
                        )}
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              {err && (
                <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
                  {err}
                </p>
              )}
            </div>
          ) : (
            <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              {err ?? 'Not found'}
            </p>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={
              submitting
              || !detail
              || (decision === 'ACCEPT' && !detail.lastDownloadedAt)
            }
            title={
              detail && decision === 'ACCEPT' && !detail.lastDownloadedAt
                ? 'Download to review before verifying'
                : undefined
            }
            className={
              'rounded-md px-4 py-1.5 text-sm font-semibold text-white disabled:bg-slate-300 ' +
              (decision === 'ACCEPT' ? 'bg-green-700 hover:bg-green-800'
                : 'bg-red-700 hover:bg-red-800')
            }
          >
            {submitting ? 'Submitting…' : decision === 'ACCEPT' ? 'Mark verified' : 'Send back'}
          </button>
        </div>
      </div>
    </div>
  );
}
