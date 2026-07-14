'use client';

import { useTrainerDashboard } from '@/components/trainer/TrainerDashboardContext';
import KPIGrid from '@/components/trainer/KPIGrid';
import TrainerFocusStrip from '@/components/trainer/TrainerFocusStrip';
import TodayMeetingsCard from '@/components/trainer/TodayMeetingsCard';
import WeeklyTrackerSummaryCard from '@/components/trainer/weeklyTracker/WeeklyTrackerSummaryCard';
import PendingDoubtsCard from '@/components/trainer/PendingDoubtsCard';
import RecentActivityCard from '@/components/trainer/RecentActivityCard';
import DashboardRefreshButton from '@/components/ui/DashboardRefreshButton';

export default function TrainerHomePage() {
  const {
    dashboard,
    dashboardLoading,
    dashboardError,
    refreshDashboard,
  } = useTrainerDashboard();

  const greeting = dashboard?.caller.firstName
    ? `Hi, ${dashboard.caller.firstName}`
    : 'Trainer Home';
  const today = new Date().toLocaleDateString(undefined, {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
  });
  const asOf = dashboard?.asOf
    ? new Date(dashboard.asOf).toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
      })
    : null;

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">{greeting}</h1>
          <p className="text-xs text-slate-500">
            {today}
            {asOf ? ` · as of ${asOf}` : ''}
          </p>
        </div>
        <DashboardRefreshButton onRefresh={refreshDashboard} />
      </header>

      {dashboardError && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {dashboardError}
        </p>
      )}

      <TrainerFocusStrip
        items={dashboard?.focusItems}
        loading={dashboardLoading && !dashboard}
      />

      <KPIGrid
        kpis={dashboard?.kpis ?? {}}
        loading={dashboardLoading && !dashboard}
      />

      <TodayMeetingsCard meetings={dashboard?.todayMeetings ?? []} />

      <WeeklyTrackerSummaryCard />

      <PendingDoubtsCard />

      <RecentActivityCard rows={dashboard?.recentActivity ?? []} />

      <footer className="border-t border-slate-200 pt-3 text-[11px] text-slate-500">
        Last updated {asOf ?? '—'} · 6 active KPIs · Role: Trainer
      </footer>
    </div>
  );
}
