'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import { Download, RefreshCw } from 'lucide-react';
import { HorizontalBars, Donut } from '@/components/erm/reports/Bars';

type HeadlineStats = {
  monthYear: string;
  activeInterns: number;
  projectsAssigned: number;
  projectsCompleted: number;
  projectsInRevision: number;
  projectsEscalated: number;
  weeklyMeetingsScheduled: number;
  weeklyMeetingsCompleted: number;
  weeklyMeetingsMissed: number;
  weeklyMeetingsCancelled: number;
  pendingReviewBacklog: number;
  averageReviewTurnaroundHours: number | null;
};

type StatusBucket = { label: string; count: number };
type MeetingBucket = { label: string; count: number };

type InternRollup = {
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  employeeId: string | null;
  technologyArea: string | null;
  projectsAssignedCount: number;
  projectsCompletedCount: number;
  projectsInRevisionCount: number;
  projectsEscalatedCount: number;
  weeklyMeetingsScheduled: number;
  weeklyMeetingsCompleted: number;
  weeklyMeetingsMissed: number;
  pendingReviewCount: number;
  averageReviewTurnaroundHours: number | null;
  latestReviewDate: string | null;
};

type Report = {
  headline: HeadlineStats;
  projectStatusDistribution: StatusBucket[];
  weeklyMeetingAttendance: MeetingBucket[];
  internRollups: InternRollup[];
};

type FilterOptions = {
  monthYears: string[];
  interns: { internLifecycleId: string; internName: string | null; employeeId: string | null }[];
};

function defaultMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

// Chart palette aligned to the 5-tone system: brand (action/in-flight),
// green (success), amber (warning), red (danger), slate (neutral).
const STATUS_COLORS: Record<string, string> = {
  Assigned: '#c2410c',      // brand-700
  Completed: '#16a34a',     // green-600
  'In Revision': '#d97706', // amber-600
  Escalated: '#dc2626',     // red-600
  Pending: '#64748b',       // slate-500
};

export default function TrainerReportsPage() {
  const [monthYear, setMonthYear] = useState<string>(defaultMonth());
  const [filters, setFilters] = useState<FilterOptions | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (monthYear) params.set('monthYear', monthYear);
      const res = await api.get<Report>(
        `/api/v1/trainer/reports/monthly-progress?${params.toString()}`,
      );
      setReport(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [monthYear]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<FilterOptions>('/api/v1/trainer/reports/filters');
        setFilters(res.data);
      } catch { /* non-fatal */ }
    })();
  }, []);

  async function downloadCsv() {
    setExporting(true);
    try {
      const params = new URLSearchParams();
      if (monthYear) params.set('monthYear', monthYear);
      const res = await api.get(
        `/api/v1/trainer/reports/monthly-progress.csv?${params.toString()}`,
        { responseType: 'blob' },
      );
      const blob = res.data as Blob;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `monthly-progress-${monthYear || 'now'}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'CSV export failed');
    } finally {
      setExporting(false);
    }
  }

  const statusSlices = useMemo(
    () => (report?.projectStatusDistribution ?? []).map((s) => ({
      label: s.label,
      value: s.count,
      color: STATUS_COLORS[s.label] ?? '#94a3b8',
    })),
    [report],
  );

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/trainer" className="hover:text-slate-700">← Trainer dashboard</Link>
        </p>
        <div className="mt-1 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">Reports</h1>
            <p className="text-xs text-slate-500">
              Monthly progress report scoped to your interns. CSV download mirrors the per-intern roll-up below.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <select value={monthYear} onChange={(e) => setMonthYear(e.target.value)}
              className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
              <option value={defaultMonth()}>Current ({defaultMonth()})</option>
              {(filters?.monthYears ?? []).filter((m) => m !== defaultMonth()).map((m) => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
            <button type="button" onClick={() => void load()}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
              <RefreshCw className="h-3.5 w-3.5" />
              Refresh
            </button>
            <button type="button" onClick={() => void downloadCsv()} disabled={exporting || !report}
              className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
              <Download className="h-3.5 w-3.5" />
              {exporting ? 'Exporting…' : 'Export CSV'}
            </button>
          </div>
        </div>
      </div>

      {err && <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>}

      {loading && !report ? (
        <div className="h-64 animate-pulse rounded-lg bg-slate-100" />
      ) : !report ? (
        <p className="text-sm text-slate-500">No data.</p>
      ) : (
        <>
          <section className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            <Card label="Active interns" value={report.headline.activeInterns} />
            <Card label="Projects assigned" value={report.headline.projectsAssigned} />
            <Card label="Projects completed" value={report.headline.projectsCompleted} tone="emerald" />
            <Card label="In revision" value={report.headline.projectsInRevision} tone="amber" />
            <Card label="Escalated" value={report.headline.projectsEscalated} tone="rose" />
            <Card label="Meetings scheduled" value={report.headline.weeklyMeetingsScheduled} />
            <Card label="Meetings completed" value={report.headline.weeklyMeetingsCompleted} tone="emerald" />
            <Card label="Meetings missed" value={report.headline.weeklyMeetingsMissed} tone="rose" />
            <Card label="Pending review backlog" value={report.headline.pendingReviewBacklog} />
            <Card label="Avg review turnaround"
              value={report.headline.averageReviewTurnaroundHours != null
                ? `${report.headline.averageReviewTurnaroundHours.toFixed(1)}h`
                : '—'} />
          </section>

          <section className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <ChartCard title="Project status distribution">
              <Donut slices={statusSlices} size={140} />
            </ChartCard>
            <ChartCard title="Weekly meeting attendance">
              <HorizontalBars
                rows={(report.weeklyMeetingAttendance ?? []).map((b) => ({
                  label: b.label, value: b.count,
                }))}
                tone="teal"
              />
            </ChartCard>
          </section>

          <section className="overflow-hidden rounded-lg border border-slate-200 bg-white">
            <header className="border-b border-slate-200 bg-slate-50 px-4 py-2">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-600">
                Per-intern roll-up — {report.headline.monthYear}
              </h3>
            </header>
            {report.internRollups.length === 0 ? (
              <p className="p-10 text-center text-sm text-slate-500">No interns in scope.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-slate-200 text-xs">
                  <thead className="bg-slate-50">
                    <tr className="text-left font-semibold uppercase tracking-wide text-slate-500">
                      <th className="px-3 py-2">Intern</th>
                      <th className="px-3 py-2 text-right">Assigned</th>
                      <th className="px-3 py-2 text-right">Completed</th>
                      <th className="px-3 py-2 text-right">Revision</th>
                      <th className="px-3 py-2 text-right">Escalated</th>
                      <th className="px-3 py-2 text-right">Meet sched</th>
                      <th className="px-3 py-2 text-right">Meet done</th>
                      <th className="px-3 py-2 text-right">Pending</th>
                      <th className="px-3 py-2 text-right">Avg turn (h)</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {report.internRollups.map((r) => (
                      <tr key={r.internLifecycleId}>
                        <td className="px-3 py-2">
                          <p className="font-medium text-slate-900">{r.internName ?? '—'}</p>
                          <p className="text-[10px] text-slate-500">
                            {r.employeeId ?? '—'}{r.technologyArea ? ` · ${r.technologyArea}` : ''}
                          </p>
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-900">{r.projectsAssignedCount}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-green-700">{r.projectsCompletedCount}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-amber-700">{r.projectsInRevisionCount}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-red-700">{r.projectsEscalatedCount}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-900">{r.weeklyMeetingsScheduled}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-900">{r.weeklyMeetingsCompleted}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-900">{r.pendingReviewCount}</td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-700">
                          {r.averageReviewTurnaroundHours != null ? r.averageReviewTurnaroundHours.toFixed(1) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <p className="text-[11px] text-slate-500">
            Filters apply to assignments + meetings inside the selected month.
            CSV export uses UTF-8 BOM + RFC 4180 (same format as ERM reports).
          </p>
        </>
      )}
    </div>
  );
}

function Card({ label, value, tone = 'slate' }: {
  label: string; value: number | string; tone?: 'slate' | 'emerald' | 'amber' | 'rose';
}) {
  const cls =
    tone === 'emerald' ? 'text-green-700' :
    tone === 'amber' ? 'text-amber-700' :
    tone === 'rose' ? 'text-red-700' :
    'text-slate-900';
  return (
    <div className="rounded-md border border-slate-200 bg-white p-3">
      <p className="text-[10px] uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-0.5 text-xl font-semibold tabular-nums ${cls}`}>{value}</p>
    </div>
  );
}

function ChartCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-slate-600">{title}</h3>
      {children}
    </div>
  );
}
