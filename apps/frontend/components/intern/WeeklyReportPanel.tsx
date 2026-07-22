'use client';

import { useEffect, useRef, useState } from 'react';
import { FileText, Send, Upload, X } from 'lucide-react';
import api from '@/lib/careers/api';
import type { WeeklyReportResponse, WeeklyReportStatus } from '@/types';

/**
 * Intern-facing weekly-report panel embedded inside a week card on the
 * timesheets page. One instance per week; each panel manages its own
 * report row (create-if-missing, attach a file, submit).
 *
 * <p>The UI is deliberately minimal — the intern uploads a report file
 * (PDF / DOC / DOCX) and clicks Submit. The narrative-text fields on
 * the WeeklyReport entity ({@code completedWork} / {@code blockers} /
 * {@code learningOutcomes} / {@code nextPlan}) still exist on the
 * backend and can be filled by other clients, but this panel doesn't
 * surface them — Ops prefers the "attach the doc, submit" flow.</p>
 *
 * <p>The parent (intern timesheets page) fetches the intern's full list
 * of reports once and passes down the row matching this
 * {@code weekStart}, or {@code null} when no row exists yet. On any
 * mutation (create / submit / attach / detach) we call
 * {@code onChanged} so the parent can refresh its cache.</p>
 */
interface Props {
  weekStart: string; // LocalDate YYYY-MM-DD
  initialReport: WeeklyReportResponse | null;
  onChanged?: (next: WeeklyReportResponse) => void;
}

export default function WeeklyReportPanel({
  weekStart,
  initialReport,
  onChanged,
}: Props) {
  const [report, setReport] = useState<WeeklyReportResponse | null>(initialReport);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Re-seed when the parent hands us a different row (e.g. after a
  // top-level reload). Keyed on id + status so identity + lifecycle
  // changes both trigger a refresh.
  const seededKey = `${initialReport?.id ?? 'none'}|${initialReport?.status ?? 'none'}`;
  const [lastSeed, setLastSeed] = useState(seededKey);
  useEffect(() => {
    if (seededKey !== lastSeed) {
      setReport(initialReport);
      setLastSeed(seededKey);
    }
  }, [seededKey, initialReport, lastSeed]);

  const locked = report?.status === 'APPROVED';
  const submitted = report != null && report.status !== 'DRAFT';
  const hasAttachment = Boolean(report?.attachmentDocumentId);

  /**
   * Ensure a WeeklyReport row exists for this week. First submit /
   * attachment upload triggers a lazy POST — no fields required.
   */
  async function ensureRow(): Promise<WeeklyReportResponse | null> {
    if (report) return report;
    setErr(null);
    try {
      const res = await api.post<WeeklyReportResponse>('/api/v1/weekly-reports', {
        weekStart,
      });
      setReport(res.data);
      onChanged?.(res.data);
      return res.data;
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Could not start report');
      return null;
    }
  }

  async function submit() {
    if (locked) return;
    setErr(null);
    setBusy(true);
    try {
      const row = await ensureRow();
      if (!row) return;
      const res = await api.put<WeeklyReportResponse>(
        `/api/v1/weekly-reports/${row.id}`,
        { submit: true },
      );
      setReport(res.data);
      onChanged?.(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Submit failed');
    } finally {
      setBusy(false);
    }
  }

  async function onFilePick(file: File) {
    setErr(null);
    setUploading(true);
    try {
      const row = await ensureRow();
      if (!row) return;
      const form = new FormData();
      form.append('file', file);
      const res = await api.post<WeeklyReportResponse>(
        `/api/v1/weekly-reports/${row.id}/attachment`,
        form,
        { headers: { 'Content-Type': 'multipart/form-data' } },
      );
      setReport(res.data);
      onChanged?.(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Upload failed');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  async function clearAttachment() {
    if (!report?.attachmentDocumentId) return;
    setErr(null);
    setUploading(true);
    try {
      const res = await api.delete<WeeklyReportResponse>(
        `/api/v1/weekly-reports/${report.id}/attachment`,
      );
      setReport(res.data);
      onChanged?.(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Could not remove attachment');
    } finally {
      setUploading(false);
    }
  }

  return (
    <section className="mt-3 rounded-md border border-slate-200 bg-slate-50 p-3">
      <header className="mb-2 flex items-center justify-between gap-2">
        <h4 className="text-sm font-semibold text-slate-900">Weekly report</h4>
        {report && <StatusPill status={report.status} />}
      </header>

      {locked && (
        <p className="mb-2 rounded-md border border-green-200 bg-green-50 px-2 py-1.5 text-xs text-green-900">
          Approved — locked for edits.
          {report?.reviewNotes ? <> Reviewer notes: {report.reviewNotes}</> : null}
        </p>
      )}
      {report?.status === 'RETURNED' && (
        <p className="mb-2 rounded-md border border-amber-200 bg-amber-50 px-2 py-1.5 text-xs text-amber-900">
          <strong>Returned for corrections.</strong>{' '}
          {report.reviewNotes ?? 'Please review and re-submit.'}
        </p>
      )}

      <div>
        <label className="text-xs font-medium text-slate-700">
          Report file{' '}
          <span className="text-slate-500">(PDF / DOC / DOCX, ≤ 10 MB)</span>
        </label>
        {hasAttachment && report?.attachmentDownloadUrl ? (
          <div className="mt-1 flex items-center gap-2 rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs">
            <FileText className="h-3.5 w-3.5 shrink-0 text-slate-500" strokeWidth={2} />
            <a
              href={report.attachmentDownloadUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="flex-1 truncate text-accent hover:underline"
            >
              {report.attachmentFileName ?? 'Attachment'}
            </a>
            {!locked && (
              <button
                type="button"
                onClick={clearAttachment}
                disabled={uploading}
                className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-50"
                aria-label="Remove attachment"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        ) : (
          <div className="mt-1">
            <input
              ref={fileInputRef}
              type="file"
              accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void onFilePick(file);
              }}
            />
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={locked || uploading}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Upload className="h-3 w-3" strokeWidth={2} />
              {uploading ? 'Uploading…' : 'Choose file…'}
            </button>
          </div>
        )}
      </div>

      {err && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 px-2 py-1 text-xs text-red-800">
          {err}
        </p>
      )}

      {!locked && (
        <footer className="mt-3 flex flex-wrap items-center justify-end gap-2">
          <button
            type="button"
            onClick={() => void submit()}
            disabled={busy || uploading}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-50"
          >
            <Send className="h-3 w-3" strokeWidth={2} />
            {busy
              ? 'Submitting…'
              : submitted
              ? 'Re-submit'
              : 'Submit for review'}
          </button>
        </footer>
      )}
    </section>
  );
}

const STATUS_STYLE: Record<WeeklyReportStatus, string> = {
  DRAFT: 'bg-slate-100 text-slate-700',
  SUBMITTED: 'bg-blue-100 text-blue-800',
  RETURNED: 'bg-amber-100 text-amber-800',
  APPROVED: 'bg-green-100 text-green-800',
};

function StatusPill({ status }: { status: WeeklyReportStatus }) {
  return (
    <span
      className={
        'inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ' +
        STATUS_STYLE[status]
      }
    >
      {status}
    </span>
  );
}
