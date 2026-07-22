'use client';

import { useEffect, useRef, useState } from 'react';
import { FileText, Save, Send, Upload, X } from 'lucide-react';
import api from '@/lib/careers/api';
import type { WeeklyReportResponse, WeeklyReportStatus } from '@/types';

/**
 * Intern-facing weekly-report panel embedded inside a week card on the
 * timesheets page. One instance per week; each panel manages its own
 * report row (create-if-missing, edit, submit, attach a file).
 *
 * <p>The parent (intern timesheets page) fetches the intern's full list
 * of reports once and passes down the row matching this
 * {@code weekStart}, or {@code null} when no row exists yet. On any
 * mutation (save draft / submit / attach / detach) we call
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
  const [completedWork, setCompletedWork] = useState(initialReport?.completedWork ?? '');
  const [blockers, setBlockers] = useState(initialReport?.blockers ?? '');
  const [learningOutcomes, setLearningOutcomes] = useState(initialReport?.learningOutcomes ?? '');
  const [nextPlan, setNextPlan] = useState(initialReport?.nextPlan ?? '');
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // If the parent re-passes a fresh report (e.g. after another WeekCard
  // mutation triggered a top-level reload), re-seed our local editable
  // state — but only when the row id or the status changes, so ongoing
  // edits aren't clobbered mid-type.
  const seededKey = `${initialReport?.id ?? 'none'}|${initialReport?.status ?? 'none'}`;
  const [lastSeed, setLastSeed] = useState(seededKey);
  useEffect(() => {
    if (seededKey !== lastSeed) {
      setReport(initialReport);
      setCompletedWork(initialReport?.completedWork ?? '');
      setBlockers(initialReport?.blockers ?? '');
      setLearningOutcomes(initialReport?.learningOutcomes ?? '');
      setNextPlan(initialReport?.nextPlan ?? '');
      setLastSeed(seededKey);
    }
  }, [seededKey, initialReport, lastSeed]);

  const locked = report?.status === 'APPROVED';
  const past_draft = report != null && report.status !== 'DRAFT';

  async function save(alsoSubmit: boolean) {
    if (locked) return;
    setErr(null);
    setSaving(true);
    try {
      const body = {
        weekStart,
        completedWork: completedWork.trim() || undefined,
        blockers: blockers.trim() || undefined,
        learningOutcomes: learningOutcomes.trim() || undefined,
        nextPlan: nextPlan.trim() || undefined,
      };
      let saved: WeeklyReportResponse;
      if (report) {
        const res = await api.put<WeeklyReportResponse>(
          `/api/v1/weekly-reports/${report.id}`,
          { ...body, submit: alsoSubmit ? true : undefined },
        );
        saved = res.data;
      } else {
        // Create the row first (DRAFT), then flip to SUBMITTED via PUT
        // if the intern hit the submit button. Two hops for the initial
        // submit — but they only happen once per week.
        const createRes = await api.post<WeeklyReportResponse>(
          '/api/v1/weekly-reports',
          body,
        );
        saved = createRes.data;
        if (alsoSubmit) {
          const putRes = await api.put<WeeklyReportResponse>(
            `/api/v1/weekly-reports/${saved.id}`,
            { submit: true },
          );
          saved = putRes.data;
        }
      }
      setReport(saved);
      onChanged?.(saved);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  async function onFilePick(file: File) {
    if (!report) {
      setErr('Save the report as a draft first, then attach a file.');
      return;
    }
    setErr(null);
    setUploading(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await api.post<WeeklyReportResponse>(
        `/api/v1/weekly-reports/${report.id}/attachment`,
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

      <div className="grid gap-2 sm:grid-cols-2">
        <TextArea
          label="Completed work"
          value={completedWork}
          onChange={setCompletedWork}
          disabled={locked}
        />
        <TextArea
          label="Blockers"
          value={blockers}
          onChange={setBlockers}
          disabled={locked}
        />
        <TextArea
          label="Learning outcomes"
          value={learningOutcomes}
          onChange={setLearningOutcomes}
          disabled={locked}
        />
        <TextArea
          label="Next plan"
          value={nextPlan}
          onChange={setNextPlan}
          disabled={locked}
        />
      </div>

      <div className="mt-3">
        <label className="text-xs font-medium text-slate-700">
          Attachment{' '}
          <span className="text-slate-500">(optional — PDF / DOC / DOCX, ≤ 10 MB)</span>
        </label>
        {report?.attachmentDownloadUrl ? (
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
              disabled={locked || uploading || !report}
              title={
                !report
                  ? 'Save the report as draft first, then attach a file'
                  : undefined
              }
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
            onClick={() => void save(false)}
            disabled={saving}
            className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            <Save className="h-3 w-3" strokeWidth={2} />
            {saving ? 'Saving…' : 'Save draft'}
          </button>
          <button
            type="button"
            onClick={() => void save(true)}
            disabled={saving}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-50"
          >
            <Send className="h-3 w-3" strokeWidth={2} />
            {saving
              ? 'Submitting…'
              : past_draft
              ? 'Re-submit'
              : 'Submit for review'}
          </button>
        </footer>
      )}
    </section>
  );
}

function TextArea({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  disabled: boolean;
}) {
  return (
    <label className="block text-xs">
      <span className="mb-1 block font-medium text-slate-700">{label}</span>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        rows={2}
        className="w-full resize-y rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs disabled:bg-slate-100"
      />
    </label>
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
