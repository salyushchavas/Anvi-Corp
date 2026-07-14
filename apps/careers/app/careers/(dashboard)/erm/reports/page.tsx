'use client';

import { useCallback, useEffect, useState } from 'react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import { Donut, HorizontalBars, VerticalBars } from '@/components/erm/reports/Bars';
import type {
  AttritionData,
  CompletionRateData,
  DecisionFunnelData,
  EvaluationDistributionData,
  PipelineFunnelData,
  ReportFilters,
  ReportType,
  TimeToHireData,
  TimesheetComplianceData,
} from '@/components/erm/reports/types';

const TABS: { key: ReportType; label: string }[] = [
  { key: 'pipeline-funnel', label: 'Pipeline' },
  { key: 'time-to-hire', label: 'Time-to-Hire' },
  { key: 'decision-funnel', label: 'Decisions' },
  { key: 'completion-rate', label: 'Completion' },
  { key: 'attrition', label: 'Attrition' },
  { key: 'evaluation-distribution', label: 'Evaluations' },
  { key: 'timesheet-compliance', label: 'Timesheets' },
];

const DEFAULT_FROM = (() => {
  const d = new Date();
  d.setDate(d.getDate() - 90);
  return d.toISOString().slice(0, 10);
})();
const DEFAULT_TO = new Date().toISOString().slice(0, 10);

export default function ReportsPage() {
  const [tab, setTab] = useState<ReportType>('pipeline-funnel');
  const [filters, setFilters] = useState<ReportFilters>({
    from: DEFAULT_FROM,
    to: DEFAULT_TO,
    scope: 'all',
  });

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Reports"
          subtitle="Pipeline, time-to-hire, completion, attrition + compliance views."
        />

        <FilterBar filters={filters} onChange={setFilters} />

        <div className="mb-3 flex flex-wrap items-center gap-2">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              onClick={() => setTab(t.key)}
              className={
                'rounded-full border px-3 py-1 text-xs font-medium ' +
                (tab === t.key
                  ? 'border-brand-700 bg-brand-700 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {t.label}
            </button>
          ))}
          <CsvButton tab={tab} filters={filters} />
        </div>

        <ReportPanel tab={tab} filters={filters} />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function FilterBar({
  filters,
  onChange,
}: {
  filters: ReportFilters;
  onChange: (f: ReportFilters) => void;
}) {
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2">
      <label className="text-xs text-slate-600">
        From{' '}
        <input
          type="date"
          value={filters.from}
          onChange={(e) => onChange({ ...filters, from: e.target.value })}
          className="ml-1 rounded-md border border-slate-200 px-2 py-1 text-xs"
        />
      </label>
      <label className="text-xs text-slate-600">
        To{' '}
        <input
          type="date"
          value={filters.to}
          onChange={(e) => onChange({ ...filters, to: e.target.value })}
          className="ml-1 rounded-md border border-slate-200 px-2 py-1 text-xs"
        />
      </label>
      <div className="ml-auto flex gap-1 rounded-md border border-slate-200 p-0.5">
        {(['mine', 'all'] as const).map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => onChange({ ...filters, scope: s })}
            className={
              'rounded px-2 py-0.5 text-xs font-medium ' +
              (filters.scope === s
                ? 'bg-slate-900 text-white'
                : 'text-slate-700')
            }
          >
            {s === 'mine' ? 'Mine' : 'All'}
          </button>
        ))}
      </div>
    </div>
  );
}

function CsvButton({
  tab,
  filters,
}: {
  tab: ReportType;
  filters: ReportFilters;
}) {
  function download() {
    const params = new URLSearchParams({
      from: filters.from,
      to: filters.to,
    });
    if (filters.scope) params.set('scope', filters.scope);
    const path = `/api/v1/erm/reports/${tab}/csv?${params.toString()}`;
    // Trigger via api so cookies/auth headers flow.
    api
      .get<Blob>(path, { responseType: 'blob' })
      .then((res) => {
        const url = URL.createObjectURL(res.data);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${tab}-${filters.to}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch((e: any) =>
        alert('CSV download failed: ' + (e?.response?.data?.error ?? e?.message)),
      );
  }
  return (
    <button
      type="button"
      onClick={download}
      className="ml-auto rounded-md border border-slate-200 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
    >
      Download CSV
    </button>
  );
}

function ReportPanel({
  tab,
  filters,
}: {
  tab: ReportType;
  filters: ReportFilters;
}) {
  const [data, setData] = useState<unknown>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const params = new URLSearchParams({
        from: filters.from,
        to: filters.to,
      });
      if (filters.scope) params.set('scope', filters.scope);
      const res = await api.get(`/api/v1/erm/reports/${tab}?${params.toString()}`);
      setData(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Failed to load report');
    } finally {
      setLoading(false);
    }
  }, [tab, filters]);

  useEffect(() => {
    void load();
  }, [load]);

  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (loading || !data) {
    return <div className="h-64 animate-pulse rounded-lg bg-slate-100" />;
  }

  switch (tab) {
    case 'pipeline-funnel':
      return <PipelineView data={data as PipelineFunnelData} />;
    case 'time-to-hire':
      return <TimeToHireView data={data as TimeToHireData} />;
    case 'decision-funnel':
      return <DecisionFunnelView data={data as DecisionFunnelData} />;
    case 'completion-rate':
      return <CompletionView data={data as CompletionRateData} />;
    case 'attrition':
      return <AttritionView data={data as AttritionData} />;
    case 'evaluation-distribution':
      return <EvaluationView data={data as EvaluationDistributionData} />;
    case 'timesheet-compliance':
      return <TimesheetView data={data as TimesheetComplianceData} />;
  }
}

function PipelineView({ data }: { data: PipelineFunnelData }) {
  const rows = data.stages.map((s) => ({
    label: s.stage,
    value: s.count,
    sub: s.conversionFromPrevious != null
      ? `${s.conversionFromPrevious.toFixed(1)}% from previous`
      : 'top of funnel',
  }));
  return (
    <Card title="Pipeline funnel">
      <p className="mb-3 text-xs text-slate-500">
        Users created in range, counted as ≥ each stage (lifecycle is
        monotonic). Unique applicants: <strong>{data.uniqueApplicants}</strong>.
      </p>
      <HorizontalBars rows={rows} />
    </Card>
  );
}

function TimeToHireView({ data }: { data: TimeToHireData }) {
  return (
    <div className="space-y-4">
      <Card title="Time-to-Hire (application → offer signed)">
        <div className="mb-3 grid grid-cols-3 gap-2 text-center">
          <Stat label="avg days" value={fmt1(data.avgDays)} />
          <Stat label="median" value={fmt1(data.medianDays)} />
          <Stat label="p90" value={fmt1(data.p90Days)} />
        </div>
        <p className="text-xs text-slate-500">
          Based on <strong>{data.signedCount}</strong> signed offers in range.
        </p>
      </Card>
      <Card title="By job type">
        <HorizontalBars
          rows={data.byJobType.map((b) => ({
            label: b.label,
            value: b.avgDays ?? 0,
            sub: `${b.count} hires`,
          }))}
          formatValue={(n) => n.toFixed(1) + 'd'}
        />
      </Card>
      <Card title="By month signed">
        <VerticalBars
          rows={data.byMonth.map((b) => ({
            label: b.label,
            value: b.avgDays ?? 0,
          }))}
        />
      </Card>
    </div>
  );
}

function DecisionFunnelView({ data }: { data: DecisionFunnelData }) {
  // 10-category palette using only the 5 brand-aligned ramps at two
  // intensities each (brand / green / amber / red / slate). Was a
  // cool-hue categorical palette (teal / indigo / sky / purple / pink).
  const palette = ['#c2410c', '#fb923c', '#15803d', '#86efac', '#92400e',
    '#fcd34d', '#b91c1c', '#fca5a5', '#334155', '#94a3b8'];
  const slices = data.decisions.map((d, i) => ({
    label: d.decision,
    value: d.count,
    color: palette[i % palette.length],
  }));
  return (
    <div className="space-y-4">
      <Card title="Application decision breakdown">
        <p className="mb-3 text-xs text-slate-500">
          {data.closedTotal} applications closed in range.
        </p>
        <Donut slices={slices} />
      </Card>
      <Card title="Top reason codes">
        <HorizontalBars
          rows={data.topReasons.map((r) => ({
            label: r.humanLabel ?? r.reasonCode,
            value: r.count,
            sub: r.reasonCode,
          }))}
          tone="rose"
        />
      </Card>
    </div>
  );
}

function CompletionView({ data }: { data: CompletionRateData }) {
  return (
    <div className="space-y-4">
      <Card title="Overall completion">
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
          <Stat label="Activated" value={data.totalActivated.toString()} />
          <Stat label="Completed" value={data.totalCompleted.toString()} />
          <Stat label="Resigned" value={data.totalResigned.toString()} />
          <Stat label="Terminated" value={data.totalTerminated.toString()} />
          <Stat label="In progress" value={data.totalInProgress.toString()} />
        </div>
      </Card>
      <CompletionTable title="By trainer" rows={data.byTrainer} />
      <CompletionTable title="By evaluator" rows={data.byEvaluator} />
      <CompletionTable title="By manager" rows={data.byManager} />
    </div>
  );
}

function CompletionTable({
  title,
  rows,
}: {
  title: string;
  rows: CompletionRateData['byTrainer'];
}) {
  if (rows.length === 0) {
    return (
      <Card title={title}>
        <p className="text-xs text-slate-500">No data.</p>
      </Card>
    );
  }
  return (
    <Card title={title}>
      <table className="min-w-full text-xs">
        <thead className="bg-slate-50 text-left text-[10px] font-semibold uppercase text-slate-500">
          <tr>
            <th className="px-2 py-1">Mentor</th>
            <th className="px-2 py-1">Activated</th>
            <th className="px-2 py-1">Completed</th>
            <th className="px-2 py-1">Resigned</th>
            <th className="px-2 py-1">Terminated</th>
            <th className="px-2 py-1">In progress</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((b, i) => (
            <tr key={i} className="border-t border-slate-100">
              <td className="px-2 py-1 text-slate-900">{b.mentorName ?? '—'}</td>
              <td className="px-2 py-1">{b.activated}</td>
              <td className="px-2 py-1 text-green-700">{b.completed}</td>
              <td className="px-2 py-1 text-amber-700">{b.resigned}</td>
              <td className="px-2 py-1 text-red-700">{b.terminated}</td>
              <td className="px-2 py-1 text-slate-600">{b.inProgress}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

function AttritionView({ data }: { data: AttritionData }) {
  // 4-category exit-type palette on the 5-tone system: green (completed),
  // amber (warning/resigned), red (terminated), slate (extended/other).
  const palette = ['#16a34a', '#d97706', '#dc2626', '#64748b'];
  const slices = data.byType.map((b, i) => ({
    label: b.exitType,
    value: b.count,
    color: palette[i % palette.length],
  }));
  return (
    <div className="space-y-4">
      <Card title="Attrition by exit type">
        <p className="mb-3 text-xs text-slate-500">
          {data.totalExited} total exits in range.
        </p>
        <Donut slices={slices} />
      </Card>
      <Card title="Top exit reasons">
        <HorizontalBars
          rows={data.topReasons.map((r) => ({
            label: r.humanLabel ?? r.reasonCode,
            value: r.count,
            sub: r.reasonCode,
          }))}
          tone="rose"
        />
      </Card>
    </div>
  );
}

function EvaluationView({ data }: { data: EvaluationDistributionData }) {
  return (
    <div className="space-y-4">
      <Card title="Monthly evaluation score histogram">
        <p className="mb-3 text-xs text-slate-500">
          {data.totalEvaluations} evaluations · avg{' '}
          <strong>{fmt2(data.avgScore)}</strong>
        </p>
        <VerticalBars
          rows={data.histogram.map((b) => ({
            label: String(b.score),
            value: b.count,
          }))}
        />
      </Card>
      <Card title="Per evaluator">
        <HorizontalBars
          rows={data.byEvaluator.map((b) => ({
            label: b.evaluatorName ?? '(unknown)',
            value: b.evaluations,
            sub: b.avgScore != null ? 'avg ' + b.avgScore.toFixed(2) : '',
          }))}
        />
      </Card>
    </div>
  );
}

function TimesheetView({ data }: { data: TimesheetComplianceData }) {
  return (
    <div className="space-y-4">
      <Card title="Aggregate compliance">
        <div className="grid grid-cols-3 gap-2 text-center">
          <Stat label="Weeks tracked" value={data.totalWeeks.toString()} />
          <Stat label="On-time %" value={fmt1(data.aggregateOnTimePct)} />
          <Stat label="First-try approved %" value={fmt1(data.aggregateFirstTryPct)} />
        </div>
      </Card>
      <Card title="Per intern">
        {data.perIntern.length === 0 ? (
          <p className="text-xs text-slate-500">No timesheets in range.</p>
        ) : (
          <table className="min-w-full text-xs">
            <thead className="bg-slate-50 text-left text-[10px] font-semibold uppercase text-slate-500">
              <tr>
                <th className="px-2 py-1">Intern</th>
                <th className="px-2 py-1">Weeks</th>
                <th className="px-2 py-1">On-time</th>
                <th className="px-2 py-1">Approved 1st</th>
                <th className="px-2 py-1">Rejected</th>
                <th className="px-2 py-1">On-time %</th>
                <th className="px-2 py-1">1st %</th>
              </tr>
            </thead>
            <tbody>
              {data.perIntern.map((b, i) => (
                <tr key={i} className="border-t border-slate-100">
                  <td className="px-2 py-1 text-slate-900">{b.internName ?? '—'}</td>
                  <td className="px-2 py-1">{b.weeksTracked}</td>
                  <td className="px-2 py-1">{b.onTimeSubmitted}</td>
                  <td className="px-2 py-1 text-green-700">
                    {b.approvedFirstTry}
                  </td>
                  <td className="px-2 py-1 text-red-700">{b.everRejected}</td>
                  <td className="px-2 py-1">{fmt1(b.onTimePct)}</td>
                  <td className="px-2 py-1">{fmt1(b.firstTryPct)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h3 className="mb-3 text-sm font-semibold text-slate-900">{title}</h3>
      {children}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-2">
      <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </div>
      <div className="text-xl font-semibold text-slate-900">{value}</div>
    </div>
  );
}

function fmt1(n: number | null | undefined) {
  return n == null ? '—' : n.toFixed(1);
}

function fmt2(n: number | null | undefined) {
  return n == null ? '—' : n.toFixed(2);
}
