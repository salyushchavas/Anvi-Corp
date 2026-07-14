'use client';

import { Suspense, useCallback, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Briefcase,
  Download,
  FileSpreadsheet,
  GraduationCap,
  Loader2,
  ShieldAlert,
  Users,
  UserX,
} from 'lucide-react';
import api from '@/lib/api';
import PeriodPicker, {
  formatPeriod,
  thisMonth,
  usePeriodFromUrl,
} from '@/components/ui/PeriodPicker';

/**
 * Phase 4B-2 — Manager Reports. Generates downloadable CSV reports
 * scoped to the Manager's own interns (managerId). Every report's
 * backend handler delegates to the same data service the dashboard /
 * roster / inactive view uses, so the exported numbers match the screen.
 *
 * <p>Format support: CSV in this phase. XLSX / PDF would need a new
 * binary export dependency (no POI / PDF lib in the project today);
 * tracked as a fast-follow.</p>
 */

type ReportType =
  | 'operations-roster'
  | 'team-workload'
  | 'training'
  | 'evaluation'
  | 'compliance-exception'
  | 'inactive';

interface ReportSpec {
  type: ReportType;
  title: string;
  description: string;
  icon: React.ReactNode;
  /** When false, "all time" is allowed and is the default. */
  periodRequired: boolean;
}

const REPORTS: ReportSpec[] = [
  {
    type: 'operations-roster',
    title: 'Operations / monthly roster',
    description:
      'The active-intern roster snapshot for the month — project, KT, evaluation, and timesheet state per intern. Matches the on-screen Active Interns roster row-for-row.',
    icon: <Users className="h-4 w-4" />,
    periodRequired: true,
  },
  {
    type: 'team-workload',
    title: 'Team workload (timesheet hours)',
    description:
      "Per-intern per-week hours + status + monthly totals — same numbers as the Timesheet Approvals weekly rollup.",
    icon: <FileSpreadsheet className="h-4 w-4" />,
    periodRequired: true,
  },
  {
    type: 'training',
    title: 'Training (project + KT)',
    description:
      'Per-intern project assignments + KT completion for the month. Mirrors the roster Project + KT columns.',
    icon: <GraduationCap className="h-4 w-4" />,
    periodRequired: true,
  },
  {
    type: 'evaluation',
    title: 'Evaluation',
    description:
      'Per-intern evaluation state + per-evaluation detail (status, period, score) for evaluations overlapping the month.',
    icon: <Briefcase className="h-4 w-4" />,
    periodRequired: true,
  },
  {
    type: 'compliance-exception',
    title: 'Compliance / exceptions (at-risk)',
    description:
      'Open exceptions across your interns from the Risk Center — type, severity, status, age. Current state (not period-scoped).',
    icon: <ShieldAlert className="h-4 w-4" />,
    periodRequired: false,
  },
  {
    type: 'inactive',
    title: 'Inactive (exits)',
    description:
      'Your interns who have exited + closure summary. Defaults to all time; pick a period to narrow by exit month.',
    icon: <UserX className="h-4 w-4" />,
    periodRequired: false,
  },
];

export default function ManagerReportsPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <ManagerReportsInner />
    </Suspense>
  );
}

function ManagerReportsInner() {
  const [period, setPeriod] = usePeriodFromUrl();
  const [selectedType, setSelectedType] = useState<ReportType>('operations-roster');
  // Compliance reports don't take a period; inactive defaults to all time.
  // Other types require a period (handled by spec.periodRequired below).
  const [allTime, setAllTime] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const spec = useMemo(
    () => REPORTS.find((r) => r.type === selectedType) ?? REPORTS[0],
    [selectedType],
  );
  const periodAllowed = !spec.periodRequired;
  const effectiveAllTime = periodAllowed && allTime;
  // Compliance is always "now"; for inactive we honour the toggle; for the
  // four period-required reports we force period and ignore allTime.
  const sendPeriod = !effectiveAllTime && spec.periodRequired
    ? period
    : (periodAllowed ? (allTime ? null : period) : null);

  const handleGenerate = useCallback(async () => {
    if (spec.periodRequired && !sendPeriod) {
      setErr('This report requires a month/year.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const params = new URLSearchParams();
      params.set('type', selectedType);
      params.set('format', 'csv');
      if (sendPeriod) {
        params.set('y', String(sendPeriod.year));
        params.set('m', String(sendPeriod.month));
      }
      const res = await api.get<Blob>(
        '/api/v1/manager/reports?' + params.toString(),
        { responseType: 'blob' },
      );
      const url = URL.createObjectURL(res.data);
      const filename = filenameFor(selectedType, sendPeriod);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      const msg = e?.response?.data?.error
        ?? e?.message
        ?? 'Download failed';
      setErr(msg);
    } finally {
      setBusy(false);
    }
  }, [selectedType, sendPeriod, spec.periodRequired]);

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">Reports</h1>
        <p className="mt-1 text-xs text-slate-500">
          CSV exports scoped to your direct reports. Numbers reconcile with the
          on-screen dashboards (same data services).
        </p>
      </header>

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">1. Pick a report</h2>
        <ul className="mt-3 grid gap-2 sm:grid-cols-2">
          {REPORTS.map((r) => (
            <li key={r.type}>
              <button
                type="button"
                onClick={() => {
                  setSelectedType(r.type);
                  setErr(null);
                }}
                className={
                  'flex w-full items-start gap-3 rounded-md border p-3 text-left transition-colors '
                  + (selectedType === r.type
                      ? 'border-brand-600 bg-brand-50'
                      : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50')
                }
                aria-pressed={selectedType === r.type}
              >
                <span className={
                  'mt-0.5 rounded-md p-1.5 '
                  + (selectedType === r.type
                      ? 'bg-brand-600 text-white'
                      : 'bg-slate-100 text-slate-600')
                }>
                  {r.icon}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-slate-900">{r.title}</span>
                  <span className="mt-0.5 block text-[11px] text-slate-600">
                    {r.description}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">2. Period + format</h2>
        <div className="mt-3 flex flex-wrap items-end gap-4">
          <div>
            <label className="text-xs font-medium text-slate-700">Period</label>
            <div className="mt-1 flex flex-wrap items-center gap-3">
              {periodAllowed && (
                <label className="inline-flex items-center gap-2 text-xs text-slate-700">
                  <input
                    type="checkbox"
                    checked={allTime}
                    onChange={(e) => {
                      setAllTime(e.target.checked);
                      if (!e.target.checked
                          && period.year === thisMonth().year
                          && period.month === thisMonth().month) {
                        setPeriod(thisMonth());
                      }
                    }}
                    className="h-4 w-4 rounded border-slate-300"
                  />
                  All time
                </label>
              )}
              {!spec.periodRequired && !periodAllowed && (
                <span className="text-xs text-slate-500">
                  (current state — not period-scoped)
                </span>
              )}
              <div className={
                (spec.periodRequired ? '' : (allTime ? 'pointer-events-none opacity-50' : ''))
              }>
                <PeriodPicker value={period} onChange={setPeriod} />
              </div>
            </div>
          </div>
          <div>
            <label className="text-xs font-medium text-slate-700">Format</label>
            <div className="mt-1">
              <span className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-slate-50 px-3 py-1.5 text-sm text-slate-700">
                <FileSpreadsheet className="h-4 w-4" /> CSV
              </span>
              <p className="mt-1 text-[10px] text-slate-500">
                XLSX / PDF — coming when binary export infra is added.
              </p>
            </div>
          </div>
        </div>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">3. Generate</h2>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
          <div className="text-xs text-slate-600">
            <p>
              <strong className="text-slate-900">{spec.title}</strong> ·{' '}
              {sendPeriod
                ? formatPeriod(sendPeriod)
                : (spec.periodRequired ? 'period required' : 'all time')}
            </p>
            <p className="mt-0.5 text-slate-500">
              File: <span className="font-mono">{filenameFor(selectedType, sendPeriod)}</span>
            </p>
          </div>
          <button
            type="button"
            onClick={handleGenerate}
            disabled={busy || (spec.periodRequired && !sendPeriod)}
            className="inline-flex items-center gap-2 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {busy
              ? <><Loader2 className="h-4 w-4 animate-spin" /> Generating…</>
              : <><Download className="h-4 w-4" /> Generate CSV</>}
          </button>
        </div>
        {err && (
          <p className="mt-3 inline-flex items-start gap-1.5 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-800">
            <AlertTriangle className="mt-0.5 h-4 w-4" />
            <span>{err}</span>
          </p>
        )}
      </section>

      <p className="text-[11px] text-slate-500">
        Each report's preamble carries the report type, period, your scope, and
        a generated-at timestamp so a downloaded file is self-describing.
        Reports are read-only and bypass nothing — same managerId scope and same
        masking as the on-screen surfaces.
      </p>
    </div>
  );
}

function filenameFor(
  type: ReportType,
  period: { year: number; month: number } | null,
): string {
  const today = new Date().toISOString().slice(0, 10);
  const periodPart = period
    ? `-${period.year}-${String(period.month).padStart(2, '0')}`
    : '-all-time';
  return `manager-${type}${periodPart}-${today}.csv`;
}
