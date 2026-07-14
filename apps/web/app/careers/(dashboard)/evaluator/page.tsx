'use client';

import Link from 'next/link';
import { AlertCircle, ArrowRight } from 'lucide-react';
import { useEvaluatorDashboard } from '@/components/evaluator/EvaluatorDashboardContext';
import type { KpiSnapshot } from '@/components/evaluator/types';
import DashboardRefreshButton from '@/components/ui/DashboardRefreshButton';

export default function EvaluatorHomePage() {
  const { dashboard, dashboardLoading, dashboardError, refreshAll } = useEvaluatorDashboard();

  const firstName = dashboard?.caller.fullName?.split(/\s+/)[0] ?? 'Evaluator';
  const activeEvalueesKpi = dashboard?.kpis.find((k) => k.key === 'ACTIVE_EVALUEES');
  const pendingAckKpi = dashboard?.kpis.find((k) => k.key === 'PENDING_ACKNOWLEDGMENTS');
  const monthLabel = dashboard?.monthYearLabel
    ?? new Date().toLocaleString(undefined, { month: 'long', year: 'numeric' });

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">
            Welcome back, {firstName}.
          </h1>
          <p className="text-xs text-slate-500">
            Monthly cycle: {monthLabel}
            {activeEvalueesKpi && pendingAckKpi && (
              <>
                {' '}· You have <strong>{activeEvalueesKpi.count}</strong> active
                evaluees and <strong>{pendingAckKpi.count}</strong> pending
                acknowledgments.
              </>
            )}
          </p>
        </div>
        <DashboardRefreshButton onRefresh={refreshAll} />
      </header>

      {dashboardError && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {dashboardError}
        </p>
      )}

      {dashboardLoading && !dashboard ? (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-24 animate-pulse rounded-lg bg-slate-100" />
          ))}
        </div>
      ) : (
        <section className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
          {(dashboard?.kpis ?? []).map((kpi) => <KpiCard key={kpi.key} snapshot={kpi} />)}
        </section>
      )}

      <footer className="border-t border-slate-200 pt-3 text-[11px] text-slate-500">
        6 KPIs · Read-only Phase · Composition + scheduling ship in Phase 2
      </footer>
    </div>
  );
}

function KpiCard({ snapshot }: { snapshot: KpiSnapshot }) {
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
        {urgent && (
          <span className="inline-flex items-center gap-0.5 rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-semibold text-red-700">
            <AlertCircle className="h-3 w-3" />
            {snapshot.urgentCount}
          </span>
        )}
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
