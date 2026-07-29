'use client';

import { useCallback, useEffect, useState } from 'react';
import { Download, FileText, Paperclip, RefreshCw } from 'lucide-react';
import api from '@/lib/careers/api';
import type { WeeklyReportsResponse } from './perproject-types';

/**
 * All-in-one weekly-report reader on the evaluator's Active Evaluee
 * detail hub. Fetches up to 100 rows in one request (backend max) so
 * a full engagement's journey renders inline without pagination —
 * evaluator sees the intern's own account of every week alongside
 * the project timeline.
 *
 * <p>Reuses the existing per-intern endpoint. Empty state renders a
 * clean neutral card; error path shows a bounded red banner and
 * a retry.</p>
 */
interface Props {
  lifecycleId: string;
}

export default function AllWeeklyReportsPanel({ lifecycleId }: Props) {
  const [data, setData] = useState<WeeklyReportsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<WeeklyReportsResponse>(
        `/api/v1/evaluator/evaluees/${lifecycleId}/weekly-reports?limit=100`,
      );
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load weekly reports');
    } finally {
      setLoading(false);
    }
  }, [lifecycleId]);
  useEffect(() => { void load(); }, [load]);

  const items = data?.items ?? [];

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <FileText className="h-4 w-4 text-brand-700" />
            <h2 className="text-sm font-semibold text-slate-900">
              Weekly reports
            </h2>
            {items.length > 0 && (
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
                {items.length}
              </span>
            )}
          </div>
          <p className="mt-0.5 text-[11px] text-slate-500">
            Intern&apos;s own account of what they built each week — newest first.
          </p>
        </div>
        <button type="button" onClick={() => void load()}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-600 hover:bg-slate-50">
          <RefreshCw className="h-3 w-3" /> Refresh
        </button>
      </div>

      {err && (
        <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}
      {loading && !data && (
        <div className="mt-3 h-24 animate-pulse rounded-md bg-slate-100" aria-hidden />
      )}
      {data && items.length === 0 && !loading && (
        <p className="mt-3 rounded-md border border-dashed border-slate-200 bg-slate-50 p-6 text-center text-sm text-slate-500">
          No weekly reports submitted yet.
        </p>
      )}
      {items.length > 0 && (
        <ol className="mt-3 max-h-[32rem] space-y-2 overflow-y-auto pr-1">
          {items.map((r) => (
            <li key={r.reportId} className="rounded-md border border-slate-200 bg-slate-50 p-3">
              <div className="flex items-center justify-between gap-2">
                <p className="text-xs font-semibold text-slate-800">
                  Week of {r.weekStart ? new Date(r.weekStart).toLocaleDateString() : '—'}
                </p>
                <div className="flex items-center gap-2 text-[10px] text-slate-500">
                  {r.status && (
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 font-semibold text-slate-700">
                      {r.status}
                    </span>
                  )}
                  {r.submittedAt && (
                    <span>Submitted {new Date(r.submittedAt).toLocaleDateString()}</span>
                  )}
                  {r.reviewedAt && (
                    <span>· Reviewed {new Date(r.reviewedAt).toLocaleDateString()}</span>
                  )}
                </div>
              </div>
              {r.completedWork && (
                <div className="mt-2">
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Completed</p>
                  <p className="mt-0.5 whitespace-pre-wrap text-xs text-slate-700">{r.completedWork}</p>
                </div>
              )}
              {r.blockers && (
                <div className="mt-2">
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Blockers</p>
                  <p className="mt-0.5 whitespace-pre-wrap text-xs text-slate-700">{r.blockers}</p>
                </div>
              )}
              {r.attachmentDocumentId && (
                <AttachmentRow
                  reportId={r.reportId}
                  fileName={r.attachmentFileName ?? null}
                  mimeType={r.attachmentMimeType ?? null}
                />
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/**
 * Fetch the attachment bytes through the authenticated axios client
 * (Bearer-token interceptor) and expose Preview / Download via a blob
 * URL. Plain {@code <a href="/api/v1/...">} anchors 404 here because
 * the browser routes them through the Next.js host and never carries
 * the token — see WeeklyReportAttachmentPreview for the same pattern.
 */
function AttachmentRow({
  reportId, fileName, mimeType,
}: { reportId: string; fileName: string | null; mimeType: string | null }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const url = `/api/v1/weekly-reports/${reportId}/attachment`;

  async function fetchBlob(): Promise<string | null> {
    setErr(null);
    try {
      const res = await api.get<Blob>(url, { responseType: 'blob' });
      return URL.createObjectURL(
        new Blob([res.data], { type: mimeType || 'application/octet-stream' }),
      );
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
        message?: string;
      };
      const status = ax.response?.status;
      const msg = ax.response?.data?.error ?? ax.message;
      // Distinguish 403 (permission) from a real 404 (missing attachment)
      // so the user gets a truthful reason instead of a generic failure.
      setErr(
        status === 403
          ? 'You do not have permission to view this attachment.'
          : status === 404
          ? 'Attachment is no longer available.'
          : msg ?? 'Failed to load attachment',
      );
      return null;
    }
  }

  async function preview() {
    setBusy(true);
    const blobUrl = await fetchBlob();
    setBusy(false);
    if (!blobUrl) return;
    window.open(blobUrl, '_blank', 'noopener,noreferrer');
    setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000);
  }

  async function download() {
    setBusy(true);
    const blobUrl = await fetchBlob();
    setBusy(false);
    if (!blobUrl) return;
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = fileName ?? 'weekly-report';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
  }

  return (
    <div className="mt-2 space-y-1">
      <div className="flex flex-wrap items-center gap-2 rounded-md border border-slate-200 bg-white p-2 text-[11px]">
        <Paperclip className="h-3 w-3 text-slate-500" />
        <span className="truncate font-mono text-slate-700">
          {fileName ?? 'attachment'}
        </span>
        {mimeType && (
          <span className="rounded bg-slate-100 px-1 py-0.5 text-[10px] text-slate-600">
            {mimeType}
          </span>
        )}
        <div className="ml-auto flex items-center gap-1">
          <button
            type="button"
            onClick={() => void preview()}
            disabled={busy}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[11px] font-medium text-brand-700 hover:bg-brand-50 disabled:opacity-60"
          >
            {busy ? 'Loading…' : 'Preview'}
          </button>
          <button
            type="button"
            onClick={() => void download()}
            disabled={busy}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[11px] font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
          >
            <Download className="h-3 w-3" /> Download
          </button>
        </div>
      </div>
      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-2 text-[11px] text-red-800">
          {err}
        </p>
      )}
    </div>
  );
}
