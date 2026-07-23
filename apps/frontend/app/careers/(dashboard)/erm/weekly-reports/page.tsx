'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { CheckCircle2, FileText, Loader2, RotateCcw } from 'lucide-react';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import WeeklyReportAttachmentPreview from '@/components/report/WeeklyReportAttachmentPreview';

/**
 * ERM verify queue for weekly reports — stage 1 of the two-stage review.
 * Mirrors {@link ../timesheets/page.tsx}: fetch all SUBMITTED rows, let
 * the ERM verify or return each. Once verified, the row leaves this queue
 * and lands on the Evaluator's queue.
 *
 * <p>The review modal is a 2-column layout: narrative text on the left,
 * inline attachment preview + download on the right (via
 * {@link WeeklyReportAttachmentPreview} — same blob-URL pattern the
 * resume viewer uses so the file actually loads under the ERM's Bearer
 * token instead of 404'ing on a raw {@code <a href>}).</p>
 */

interface WeeklyReportRow {
  id: string;
  internCandidateId: string | null;
  internName: string | null;
  weekStart: string;
  completedWork: string | null;
  blockers: string | null;
  learningOutcomes: string | null;
  nextPlan: string | null;
  status: 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'RETURNED' | 'APPROVED';
  submittedAt: string | null;
  reviewNotes: string | null;
  ermNotes: string | null;
  verifiedByName: string | null;
  verifiedAt: string | null;
  attachmentDocumentId: string | null;
  attachmentDownloadUrl: string | null;
  attachmentFileName: string | null;
  attachmentFileSize: number | null;
  attachmentMimeType: string | null;
}

export default function ErmWeeklyReportsPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <ErmWeeklyReportsInner />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function ErmWeeklyReportsInner() {
  const [rows, setRows] = useState<WeeklyReportRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [returnFor, setReturnFor] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<WeeklyReportRow[]>('/api/v1/erm/weekly-reports');
      setRows(res.data ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const openReport = useMemo(
    () => rows.find((r) => r.id === openId) ?? null,
    [rows, openId],
  );

  async function verify(id: string, ermNotes?: string) {
    try {
      await api.post(`/api/v1/erm/weekly-reports/${id}/verify`,
        ermNotes ? { ermNotes } : {});
      setOpenId(null);
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Verify failed');
    }
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">Weekly Reports — verify</h1>
        <p className="text-xs text-slate-500">
          Stage 1 of the two-stage review. Verified rows move to the Evaluator&apos;s queue.
        </p>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      {loading ? (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      ) : rows.length === 0 ? (
        <p className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
          No submitted weekly reports awaiting your verification.
        </p>
      ) : (
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Week</th>
                <th className="px-3 py-2">Submitted</th>
                <th className="px-3 py-2">Attachment</th>
                <th className="px-3 py-2 text-right"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="px-3 py-2 text-sm font-medium text-slate-900">
                    {r.internName ?? '—'}
                  </td>
                  <td className="px-3 py-2 text-xs text-slate-700">{r.weekStart}</td>
                  <td className="px-3 py-2 text-xs text-slate-700">
                    {r.submittedAt ? new Date(r.submittedAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-3 py-2 text-xs">
                    {r.attachmentDocumentId ? (
                      <span
                        className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-slate-700"
                        title={r.attachmentFileName ?? undefined}
                      >
                        <FileText className="h-3 w-3" />
                        {r.attachmentFileName ?? 'file'}
                      </span>
                    ) : (
                      <span className="text-slate-400">—</span>
                    )}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => setOpenId(r.id)}
                      className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
                    >
                      Review
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {openReport && (
        <ReviewModal
          report={openReport}
          onClose={() => setOpenId(null)}
          onVerify={(notes) => verify(openReport.id, notes)}
          onReturn={() => { setReturnFor(openReport.id); setOpenId(null); }}
        />
      )}

      {returnFor && (
        <ReturnModal
          endpoint={`/api/v1/erm/weekly-reports/${returnFor}/return`}
          onClose={() => setReturnFor(null)}
          onDone={async () => { setReturnFor(null); await load(); }}
        />
      )}
    </div>
  );
}

function ReviewModal({
  report, onClose, onVerify, onReturn,
}: {
  report: WeeklyReportRow;
  onClose: () => void;
  onVerify: (ermNotes: string) => Promise<void>;
  onReturn: () => void;
}) {
  const [ermNotes, setErmNotes] = useState('');
  const [saving, setSaving] = useState(false);

  async function submit() {
    setSaving(true);
    try { await onVerify(ermNotes.trim()); } finally { setSaving(false); }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}>
      <div className="flex max-h-[92vh] w-full max-w-6xl flex-col rounded-lg bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}>
        <header className="border-b border-slate-200 px-5 py-3">
          <h2 className="text-base font-semibold text-slate-900">
            {report.internName ?? 'Report'} · week of {report.weekStart}
          </h2>
          <p className="text-[11px] text-slate-500">
            Submitted {report.submittedAt ? new Date(report.submittedAt).toLocaleString() : '—'}
          </p>
        </header>

        <div className="grid flex-1 grid-cols-1 gap-4 overflow-y-auto p-5 lg:grid-cols-2">
          {/* LEFT — narrative + ERM note textarea */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              Report content
            </h3>
            <NarrativeBlock label="Completed work" value={report.completedWork} />
            <NarrativeBlock label="Blockers" value={report.blockers} />
            <NarrativeBlock label="Learning outcomes" value={report.learningOutcomes} />
            <NarrativeBlock label="Next plan" value={report.nextPlan} />

            <label className="block pt-1">
              <span className="text-xs font-semibold text-slate-700">
                ERM verification note (optional)
              </span>
              <textarea
                value={ermNotes}
                onChange={(e) => setErmNotes(e.target.value)}
                rows={3}
                placeholder="Any context to hand off to the Evaluator."
                className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
              />
            </label>
          </section>

          {/* RIGHT — inline attachment preview */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              Attachment
            </h3>
            <WeeklyReportAttachmentPreview
              attachment={{
                documentId: report.attachmentDocumentId,
                fileName: report.attachmentFileName,
                fileSize: report.attachmentFileSize,
                mimeType: report.attachmentMimeType,
                downloadUrl: report.attachmentDownloadUrl,
              }}
              height={620}
            />
          </section>
        </div>

        <footer className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
          <button type="button" onClick={onReturn}
            className="inline-flex items-center gap-1 rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-sm font-semibold text-amber-800 hover:bg-amber-100">
            <RotateCcw className="h-3.5 w-3.5" />
            Return for correction
          </button>
          <button type="button" onClick={submit} disabled={saving}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
            {saving ? 'Verifying…' : 'Verify · send to Evaluator'}
          </button>
        </footer>
      </div>
    </div>
  );
}

function ReturnModal({
  endpoint, onClose, onDone,
}: {
  endpoint: string;
  onClose: () => void;
  onDone: () => Promise<void> | void;
}) {
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (notes.trim().length < 5) {
      setErr('Please give the intern at least a short reason (5+ chars).');
      return;
    }
    setSaving(true); setErr(null);
    try {
      await api.post(endpoint, { reviewNotes: notes.trim() });
      await onDone();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Return failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}>
      <div className="w-full max-w-lg rounded-lg bg-white p-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}>
        <h2 className="text-base font-semibold text-slate-900">Return for correction</h2>
        <p className="mt-1 text-xs text-slate-500">
          The intern gets an email + in-app notice with these notes.
        </p>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={5}
          placeholder="What needs to change before this can be verified?"
          className="mt-3 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        />
        {err && <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
        <div className="mt-3 flex justify-end gap-2">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
          <button type="button" onClick={submit} disabled={saving}
            className="inline-flex items-center gap-1 rounded-md bg-amber-600 px-4 py-1.5 text-sm font-semibold text-white hover:bg-amber-700 disabled:bg-slate-300">
            {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
            {saving ? 'Sending…' : 'Send back to intern'}
          </button>
        </div>
      </div>
    </div>
  );
}

function NarrativeBlock({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 whitespace-pre-wrap rounded-md border border-slate-200 bg-slate-50 p-2 text-xs text-slate-700">
        {value?.trim() ? value : <span className="italic text-slate-400">(none)</span>}
      </p>
    </div>
  );
}
