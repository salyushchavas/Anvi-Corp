'use client';

import Link from 'next/link';
import { AlertCircle, ArrowRight } from 'lucide-react';
import { useEvaluatorDashboard } from '@/components/evaluator/EvaluatorDashboardContext';
import type { KpiSnapshot } from '@/components/evaluator/types';
import AwaitingEvaluationCard from '@/components/evaluator/AwaitingEvaluationCard';
import DashboardRefreshButton from '@/components/ui/DashboardRefreshButton';
import MonthPicker from '@/components/common/MonthPicker';

/**
 * KPIs that are LIVE queues — they don't change with the month
 * selector and stay pinned to "right now". When a past month is
 * selected the mix of month-scoped + live KPIs on the same grid can
 * be confusing, so live ones show a small neutral "Live" tag.
 */
const LIVE_KPI_KEYS = new Set([
  'STEM_OPT_INTERNS',
  'UPCOMING_I983',
]);

export default function EvaluatorHomePage() {
  const {
    dashboard, dashboardLoading, dashboardError, refreshAll,
    selectedMonth, setSelectedMonth, isCurrentMonth,
  } = useEvaluatorDashboard();

  const firstName = dashboard?.caller.fullName?.split(/\s+/)[0] ?? 'Evaluator';
  const activeEvalueesKpi = dashboard?.kpis.find((k) => k.key === 'ACTIVE_EVALUEES');
  const pendingAckKpi = dashboard?.kpis.find((k) => k.key === 'PENDING_ACKNOWLEDGMENTS');

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">
            Welcome back, {firstName}.
          </h1>
          <p className="text-xs text-slate-500">
            Per-project evaluations
            {activeEvalueesKpi && pendingAckKpi && (
              <>
                {' '}· <strong>{activeEvalueesKpi.count}</strong> active
                evaluees, <strong>{pendingAckKpi.count}</strong> pending
                acknowledgments
              </>
            )}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <MonthPicker value={selectedMonth} onChange={setSelectedMonth} />
          <DashboardRefreshButton onRefresh={refreshAll} />
        </div>
      </header>

      {dashboardError && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {dashboardError}
        </p>
      )}

      {/* Primary worklist — completed projects awaiting this evaluator's
          action. Sorted oldest-first server-side. */}
      <AwaitingEvaluationCard />

      {dashboardLoading && !dashboard ? (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-24 animate-pulse rounded-lg bg-slate-100" />
          ))}
        </div>
      ) : (
        <section className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
          {(dashboard?.kpis ?? []).map((kpi) => (
            <KpiCard
              key={kpi.key}
              snapshot={kpi}
              showLiveTag={!isCurrentMonth && LIVE_KPI_KEYS.has(kpi.key)}
            />
          ))}
        </section>
      )}
    </div>
  );
}

function KpiCard({
  snapshot, showLiveTag,
}: { snapshot: KpiSnapshot; showLiveTag: boolean }) {
  const urgent = snapshot.urgentCount > 0;
  return (
    <Link
      href={snapshot.actionUrl}
      className="group rounded-lg border border-slate-200 bg-white p-4 hover:border-brand-300 hover:shadow-sm"
    >
      <div className="flex items-center justify-between">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          {snapshot.label}
        </p>
        <div className="flex items-center gap-1">
          {showLiveTag && (
            <span
              className="inline-flex items-center gap-0.5 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-medium text-slate-600"
              title="This tile always reflects the current moment — it doesn't change with the month you've picked."
            >
              Live
            </span>
          )}
          {urgent && (
            <span className="inline-flex items-center gap-0.5 rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-semibold text-red-700">
              <AlertCircle className="h-3 w-3" />
              {snapshot.urgentCount}
            </span>
          )}
        </div>
      </div>
      <p className="mt-1 text-3xl font-semibold tabular-nums text-slate-900">
        {snapshot.count.toLocaleString()}
      </p>
      <p className="mt-1 min-h-[1rem] text-[11px] text-slate-500">
        {snapshot.helperText ?? ' '}
      </p>
      <p className="mt-2 inline-flex items-center gap-0.5 text-[11px] font-medium text-brand-700 opacity-0 transition group-hover:opacity-100">
        Open
        <ArrowRight className="h-3 w-3" />
      </p>
    </Link>
  );
}
