'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowLeft, CheckCircle2, FileText, Loader2, RotateCcw } from 'lucide-react';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import { useErmDashboard } from '@/components/erm/ErmDashboardContext';
import WeeklyReportAttachmentPreview from '@/components/report/WeeklyReportAttachmentPreview';

/**
 * ERM verify queue for weekly reports — stage 1 of the two-stage review.
 * Rebuilt as a two-level view because the flat list repeats the same
 * intern across many rows and doesn't scale.
 *
 * <ul>
 *   <li>Level 1 — one card per intern with a pending-count badge, oldest
 *       pending week and the latest submitted timestamp. Sorted
 *       oldest-first so the natural top-of-list is the row most at risk
 *       of falling out of SLA.</li>
 *   <li>Level 2 — click an intern → their pending weeks stacked
 *       chronologically. Each panel shows the narrative text + inline
 *       attachment preview + per-report Verify / Return actions. A
 *       checkbox in the panel header opts a row into bulk verify;
 *       "Verify selected" POSTs
 *       {@code /api/v1/erm/weekly-reports/verify-batch}. Return stays
 *       per-report (needs individual notes).</li>
 * </ul>
 *
 * <p>Backing data is a single flat fetch of every SUBMITTED report
 * (org-wide); grouping happens client-side. The volume never gets past
 * "a few dozen rows" in practice — pagination + server-side grouping
 * would be over-engineered for that scale.</p>
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
  latestSubmittedAt: string | null;
  rows: WeeklyReportRow[];
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
  const { selectedMonth, isCurrentMonth } = useErmDashboard();
  const [rows, setRows] = useState<WeeklyReportRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [activeIntern, setActiveIntern] = useState<string | null>(null);
  const [returnFor, setReturnFor] = useState<WeeklyReportRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const url = isCurrentMonth
        ? '/api/v1/erm/weekly-reports'
        : `/api/v1/erm/weekly-reports?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<WeeklyReportRow[]>(url);
      setRows(res.data ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [selectedMonth, isCurrentMonth]);
  useEffect(() => { void load(); }, [load]);

  const groups = useMemo(() => groupByIntern(rows), [rows]);
  const openGroup = useMemo(
    () => groups.find((g) => g.candidateId === activeIntern) ?? null,
    [groups, activeIntern],
  );

  async function verifyOne(id: string, ermNotes?: string) {
    try {
      await api.post(`/api/v1/erm/weekly-reports/${id}/verify`,
        ermNotes ? { ermNotes } : {});
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Verify failed');
    }
  }

  async function verifyBatch(ids: string[]): Promise<{ verified: number; skipped: number }> {
    if (ids.length === 0) return { verified: 0, skipped: 0 };
    try {
      const res = await api.post<Record<string, string>>(
        '/api/v1/erm/weekly-reports/verify-batch',
        { ids });
      const entries = Object.values(res.data ?? {});
      const verified = entries.filter((v) => v === 'VERIFIED').length;
      await load();
      return { verified, skipped: entries.length - verified };
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Batch verify failed');
      return { verified: 0, skipped: ids.length };
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
          {openGroup ? openGroup.internName : 'Weekly Reports — verify'}
        </h1>
        <p className="text-xs text-slate-500">
          {openGroup
            ? `${openGroup.pending} weekly report${openGroup.pending === 1 ? '' : 's'} awaiting your verification.`
            : 'Stage 1 of the two-stage review. Verified rows move to the Evaluator’s queue.'}
        </p>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      {loading ? (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      ) : groups.length === 0 ? (
        <p className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
          No submitted weekly reports awaiting your verification.
        </p>
      ) : openGroup ? (
        <InternDetail
          group={openGroup}
          onVerifyOne={verifyOne}
          onVerifyBatch={verifyBatch}
          onReturn={(r) => setReturnFor(r)}
        />
      ) : (
        <InternList groups={groups} onOpen={(id) => setActiveIntern(id)} />
      )}

      {returnFor && (
        <ReturnModal
          endpoint={`/api/v1/erm/weekly-reports/${returnFor.id}/return`}
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
    <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50">
          <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
            <th className="px-3 py-2">Intern</th>
            <th className="px-3 py-2">Pending</th>
            <th className="px-3 py-2">Oldest week</th>
            <th className="px-3 py-2">Latest submitted</th>
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
                {g.latestSubmittedAt ? new Date(g.latestSubmittedAt).toLocaleString() : '—'}
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

/* ── Level 2 — one intern's pending weeks ─────────────────────────── */

function InternDetail({
  group, onVerifyOne, onVerifyBatch, onReturn,
}: {
  group: InternGroup;
  onVerifyOne: (id: string, ermNotes?: string) => Promise<void>;
  onVerifyBatch: (ids: string[]) => Promise<{ verified: number; skipped: number }>;
  onReturn: (r: WeeklyReportRow) => void;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [batchSaving, setBatchSaving] = useState(false);
  const [flash, setFlash] = useState<string | null>(null);
  const [ermNotesById, setErmNotesById] = useState<Record<string, string>>({});

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }
  function toggleAll() {
    if (selected.size === group.rows.length) setSelected(new Set());
    else setSelected(new Set(group.rows.map((r) => r.id)));
  }

  async function verifySelected() {
    if (selected.size === 0) return;
    setBatchSaving(true); setFlash(null);
    const { verified, skipped } = await onVerifyBatch(Array.from(selected));
    setSelected(new Set());
    setFlash(
      `Verified ${verified} of ${verified + skipped}` +
      (skipped > 0 ? ` (${skipped} skipped — see individual rows).` : '.'),
    );
    setBatchSaving(false);
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 bg-white p-3">
        <label className="flex items-center gap-2 text-xs font-medium text-slate-700">
          <input
            type="checkbox"
            checked={selected.size === group.rows.length && group.rows.length > 0}
            onChange={toggleAll}
            className="h-3.5 w-3.5"
          />
          Select all ({group.rows.length})
        </label>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">
            {selected.size} selected
          </span>
          <button
            type="button"
            onClick={verifySelected}
            disabled={selected.size === 0 || batchSaving}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            {batchSaving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
            Verify selected
          </button>
        </div>
      </div>

      {flash && (
        <p className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-xs text-emerald-800">
          {flash}
        </p>
      )}

      {group.rows.map((r) => (
        <ReportPanel
          key={r.id}
          report={r}
          selected={selected.has(r.id)}
          onToggle={() => toggle(r.id)}
          ermNotes={ermNotesById[r.id] ?? ''}
          onErmNotesChange={(v) => setErmNotesById((prev) => ({ ...prev, [r.id]: v }))}
          onVerify={() => onVerifyOne(r.id, (ermNotesById[r.id] ?? '').trim() || undefined)}
          onReturn={() => onReturn(r)}
        />
      ))}
    </div>
  );
}

function ReportPanel({
  report, selected, onToggle, ermNotes, onErmNotesChange, onVerify, onReturn,
}: {
  report: WeeklyReportRow;
  selected: boolean;
  onToggle: () => void;
  ermNotes: string;
  onErmNotesChange: (v: string) => void;
  onVerify: () => Promise<void>;
  onReturn: () => void;
}) {
  const [saving, setSaving] = useState(false);
  async function submit() {
    setSaving(true);
    try { await onVerify(); } finally { setSaving(false); }
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
        <label className="flex items-center gap-3 text-sm font-medium text-slate-900">
          <input
            type="checkbox"
            checked={selected}
            onChange={onToggle}
            className="h-3.5 w-3.5"
          />
          Week of {report.weekStart}
          <span className="text-[11px] font-normal text-slate-500">
            · submitted {report.submittedAt ? new Date(report.submittedAt).toLocaleString() : '—'}
          </span>
        </label>
        <div className="flex items-center gap-2">
          <button type="button" onClick={onReturn}
            className="inline-flex items-center gap-1 rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-800 hover:bg-amber-100">
            <RotateCcw className="h-3 w-3" /> Return
          </button>
          <button type="button" onClick={submit} disabled={saving}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            {saving ? <Loader2 className="h-3 w-3 animate-spin" /> : <CheckCircle2 className="h-3 w-3" />}
            Verify
          </button>
        </div>
      </header>

      <div className="grid grid-cols-1 gap-4 p-4 lg:grid-cols-2">
        <div className="space-y-3">
          <NarrativeBlock label="Completed work" value={report.completedWork} />
          <NarrativeBlock label="Blockers" value={report.blockers} />
          <NarrativeBlock label="Learning outcomes" value={report.learningOutcomes} />
          <NarrativeBlock label="Next plan" value={report.nextPlan} />
          <label className="block pt-1">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              ERM verification note (optional)
            </span>
            <textarea
              value={ermNotes}
              onChange={(e) => onErmNotesChange(e.target.value)}
              rows={2}
              placeholder="Any context to hand off to the Evaluator."
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

/* ── Client-side grouping ────────────────────────────────────────── */

function groupByIntern(rows: WeeklyReportRow[]): InternGroup[] {
  const map = new Map<string, InternGroup>();
  for (const r of rows) {
    // Fall back to a synthetic key when candidate id is missing so
    // orphan rows still surface instead of silently collapsing to one.
    const key = r.internCandidateId ?? `unknown:${r.id}`;
    const name = r.internName ?? '(unknown intern)';
    const g = map.get(key);
    if (!g) {
      map.set(key, {
        candidateId: key,
        internName: name,
        pending: 1,
        oldestWeekStart: r.weekStart,
        latestSubmittedAt: r.submittedAt,
        rows: [r],
      });
    } else {
      g.pending += 1;
      g.rows.push(r);
      if (r.weekStart < g.oldestWeekStart) g.oldestWeekStart = r.weekStart;
      if (r.submittedAt && (!g.latestSubmittedAt || r.submittedAt > g.latestSubmittedAt)) {
        g.latestSubmittedAt = r.submittedAt;
      }
    }
  }
  // Sort rows within each intern by weekStart asc (chronological
  // review order).
  for (const g of map.values()) {
    g.rows.sort((a, b) => a.weekStart.localeCompare(b.weekStart));
  }
  // Sort interns by oldest-pending-first so the top of the list is
  // always the row most at risk of missing SLA.
  return Array.from(map.values()).sort((a, b) =>
    a.oldestWeekStart.localeCompare(b.oldestWeekStart));
}
