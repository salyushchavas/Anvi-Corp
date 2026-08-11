'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowLeft, CheckCircle2, Loader2, RotateCcw } from 'lucide-react';
import api from '@/lib/careers/api';
import ConfirmDialog from '@/components/ConfirmDialog';
import WeeklyReportAttachmentPreview from '@/components/report/WeeklyReportAttachmentPreview';

/**
 * Manager approve queue for weekly reports — stage 2 of the two-stage
 * review. Same two-level layout as the ERM queue (intern list →
 * per-intern chronological weeks) so the two screens feel identical.
 *
 * <p>Approve is per-report only. Bulk approve isn&apos;t offered because
 * approve is the terminal signoff — no reason to hide individual sign-off
 * behind a batch button when the ERM has already thinned the queue for
 * this stage.</p>
 *
 * <p>Wrapping is handled by the {@code /careers/manager} layout
 * ({@code ProtectedRoute requiredRoles=['MANAGER','SUPER_ADMIN']} +
 * {@code ManagerSidebar}), so this page renders its own content directly.</p>
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

interface InternGroup {
  candidateId: string;
  internName: string;
  pending: number;
  oldestWeekStart: string;
  latestVerifiedAt: string | null;
  rows: WeeklyReportRow[];
}

export default function ManagerWeeklyReportsPage() {
  const [rows, setRows] = useState<WeeklyReportRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [activeIntern, setActiveIntern] = useState<string | null>(null);
  const [returnFor, setReturnFor] = useState<WeeklyReportRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<WeeklyReportRow[]>('/api/v1/manager/weekly-reports/pending');
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

  const groups = useMemo(() => groupByIntern(rows), [rows]);
  const openGroup = useMemo(
    () => groups.find((g) => g.candidateId === activeIntern) ?? null,
    [groups, activeIntern],
  );

  async function approveOne(id: string, reviewNotes?: string) {
    try {
      await api.post(`/api/v1/weekly-reports/${id}/approve`,
        reviewNotes ? { reviewNotes } : {});
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Approve failed');
    }
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      {openGroup ? (
        <button
          type="button"
          onClick={() => setActiveIntern(null)}
          className="inline-flex items-center gap-1 text-xs font-medium text-slate-600 hover:text-slate-900"
        >
          <ArrowLeft className="h-3.5 w-3.5" /> All interns
        </button>
      ) : null}

      <header>
        <h1 className="text-xl font-semibold text-slate-900">
          {openGroup ? openGroup.internName : 'Weekly Reports — approve'}
        </h1>
        <p className="text-xs text-slate-500">
          {openGroup
            ? `${openGroup.pending} verified week${openGroup.pending === 1 ? '' : 's'} awaiting your approval.`
            : 'Stage 2 of the two-stage review. Approve is terminal; return sends the report back to the intern.'}
        </p>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      {loading ? (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      ) : groups.length === 0 ? (
        <p className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
          No verified reports awaiting your approval.
        </p>
      ) : openGroup ? (
        <InternDetail
          group={openGroup}
          onApproveOne={approveOne}
          onReturn={(r) => setReturnFor(r)}
        />
      ) : (
        <InternList groups={groups} onOpen={(id) => setActiveIntern(id)} />
      )}

      {returnFor && (
        <ReturnModal
          endpoint={`/api/v1/manager/weekly-reports/${returnFor.id}/return`}
          weekStart={returnFor.weekStart}
          onClose={() => setReturnFor(null)}
          onDone={async () => { setReturnFor(null); await load(); }}
        />
      )}
    </div>
  );
}

/* ── Level 1 — intern list ────────────────────────────────────────── */

function InternList({
  groups, onOpen,
}: { groups: InternGroup[]; onOpen: (candidateId: string) => void }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50">
          <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
            <th className="px-3 py-2">Intern</th>
            <th className="px-3 py-2">Pending</th>
            <th className="px-3 py-2">Oldest week</th>
            <th className="px-3 py-2">Latest verified</th>
            <th className="px-3 py-2 text-right"></th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {groups.map((g) => (
            <tr key={g.candidateId}>
              <td className="px-3 py-2 text-sm font-medium text-slate-900">
                {g.internName}
              </td>
              <td className="px-3 py-2 text-xs">
                <span className="inline-flex items-center rounded-full bg-brand-50 px-2 py-0.5 font-semibold text-brand-800">
                  {g.pending}
                </span>
              </td>
              <td className="px-3 py-2 text-xs text-slate-700">{g.oldestWeekStart}</td>
              <td className="px-3 py-2 text-xs text-slate-700">
                {g.latestVerifiedAt ? new Date(g.latestVerifiedAt).toLocaleString() : '—'}
              </td>
              <td className="px-3 py-2 text-right">
                <button
                  type="button"
                  onClick={() => onOpen(g.candidateId)}
                  className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
                >
                  Review weeks
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ── Level 2 — one intern's verified weeks ────────────────────────── */

function InternDetail({
  group, onApproveOne, onReturn,
}: {
  group: InternGroup;
  onApproveOne: (id: string, reviewNotes?: string) => Promise<void>;
  onReturn: (r: WeeklyReportRow) => void;
}) {
  const [approveNotesById, setApproveNotesById] = useState<Record<string, string>>({});
  return (
    <div className="space-y-4">
      {group.rows.map((r) => (
        <ReportPanel
          key={r.id}
          report={r}
          approveNotes={approveNotesById[r.id] ?? ''}
          onApproveNotesChange={(v) => setApproveNotesById((prev) => ({ ...prev, [r.id]: v }))}
          onApprove={() => onApproveOne(r.id, (approveNotesById[r.id] ?? '').trim() || undefined)}
          onReturn={() => onReturn(r)}
        />
      ))}
    </div>
  );
}

function ReportPanel({
  report, approveNotes, onApproveNotesChange, onApprove, onReturn,
}: {
  report: WeeklyReportRow;
  approveNotes: string;
  onApproveNotesChange: (v: string) => void;
  onApprove: () => Promise<void>;
  onReturn: () => void;
}) {
  const [saving, setSaving] = useState(false);
  const [approveConfirmOpen, setApproveConfirmOpen] = useState(false);
  async function submit() {
    setSaving(true);
    try { await onApprove(); } finally { setSaving(false); setApproveConfirmOpen(false); }
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
        <div className="text-sm font-medium text-slate-900">
          Week of {report.weekStart}
          <span className="ml-2 text-[11px] font-normal text-slate-500">
            · verified by {report.verifiedByName ?? '—'}
            {report.verifiedAt && ` on ${new Date(report.verifiedAt).toLocaleDateString()}`}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button type="button" onClick={onReturn}
            className="inline-flex items-center gap-1 rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-800 hover:bg-amber-100">
            <RotateCcw className="h-3 w-3" /> Return
          </button>
          <button type="button" onClick={() => setApproveConfirmOpen(true)} disabled={saving}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            {saving ? <Loader2 className="h-3 w-3 animate-spin" /> : <CheckCircle2 className="h-3 w-3" />}
            Approve
          </button>
        </div>
      </header>

      {/* Themed confirmation — Approve is terminal (locks the week's
          report for the intern), so a single-click was too easy to
          fire by accident. Optional approval note above is respected
          — it's already in `approveNotes` when submit fires. */}
      <ConfirmDialog
        open={approveConfirmOpen}
        onClose={() => setApproveConfirmOpen(false)}
        onConfirm={submit}
        title={`Approve weekly report for ${report.weekStart}?`}
        description="Approval is terminal — the intern can no longer edit this week's report. Optional note above (if any) will be included."
        confirmLabel="Approve report"
        variant="primary"
      />

      <div className="grid grid-cols-1 gap-4 p-4 lg:grid-cols-2">
        <div className="space-y-3">
          <NarrativeBlock label="Completed work" value={report.completedWork} />
          <NarrativeBlock label="Blockers" value={report.blockers} />
          <NarrativeBlock label="Learning outcomes" value={report.learningOutcomes} />
          <NarrativeBlock label="Next plan" value={report.nextPlan} />
          {report.ermNotes && (
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                ERM verification note
              </p>
              <p className="mt-1 whitespace-pre-wrap rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-800">
                {report.ermNotes}
              </p>
            </div>
          )}
          <label className="block pt-1">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              Approval note (optional)
            </span>
            <textarea
              value={approveNotes}
              onChange={(e) => onApproveNotesChange(e.target.value)}
              rows={2}
              placeholder="Optional wrap-up comment for the intern."
              className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-xs"
            />
          </label>
        </div>
        <div className="space-y-2">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
            Attachment
          </p>
          <WeeklyReportAttachmentPreview
            attachment={{
              documentId: report.attachmentDocumentId,
              fileName: report.attachmentFileName,
              fileSize: report.attachmentFileSize,
              mimeType: report.attachmentMimeType,
              downloadUrl: report.attachmentDownloadUrl,
            }}
            height={520}
          />
        </div>
      </div>
    </section>
  );
}

function ReturnModal({
  endpoint, weekStart, onClose, onDone,
}: {
  endpoint: string;
  weekStart: string;
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
        <h2 className="text-base font-semibold text-slate-900">
          Return week of {weekStart}
        </h2>
        <p className="mt-1 text-xs text-slate-500">
          The intern gets an email + in-app notice with these notes; the ERM verify
          stamp will be cleared so a re-submission re-runs the full flow.
        </p>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={5}
          placeholder="What still needs revision?"
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

/* ── Client-side grouping ────────────────────────────────────────── */

function groupByIntern(rows: WeeklyReportRow[]): InternGroup[] {
  const map = new Map<string, InternGroup>();
  for (const r of rows) {
    const key = r.internCandidateId ?? `unknown:${r.id}`;
    const name = r.internName ?? '(unknown intern)';
    const g = map.get(key);
    if (!g) {
      map.set(key, {
        candidateId: key,
        internName: name,
        pending: 1,
        oldestWeekStart: r.weekStart,
        latestVerifiedAt: r.verifiedAt,
        rows: [r],
      });
    } else {
      g.pending += 1;
      g.rows.push(r);
      if (r.weekStart < g.oldestWeekStart) g.oldestWeekStart = r.weekStart;
      if (r.verifiedAt && (!g.latestVerifiedAt || r.verifiedAt > g.latestVerifiedAt)) {
        g.latestVerifiedAt = r.verifiedAt;
      }
    }
  }
  for (const g of map.values()) {
    g.rows.sort((a, b) => a.weekStart.localeCompare(b.weekStart));
  }
  return Array.from(map.values()).sort((a, b) =>
    a.oldestWeekStart.localeCompare(b.oldestWeekStart));
}
