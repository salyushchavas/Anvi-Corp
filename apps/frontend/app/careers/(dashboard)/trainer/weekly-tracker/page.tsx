'use client';

import { useMemo } from 'react';
import WeeklyTrackerGrid from '@/components/trainer/weeklyTracker/WeeklyTrackerGrid';
import { useTrainerDashboard } from '@/components/trainer/TrainerDashboardContext';

export default function TrainerWeeklyTrackerPage() {
  const { selectedMonth, isCurrentMonth } = useTrainerDashboard();
  const period = useMemo(() => {
    const [ys, ms] = selectedMonth.split('-');
    const y = Number(ys), m = Number(ms);
    return Number.isNaN(y) || Number.isNaN(m) ? null : { y, m };
  }, [selectedMonth]);

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">
          Weekly Sessions
        </h1>
        <p className="text-xs text-slate-500">
          Status of every active intern&rsquo;s weekly session for{' '}
          {isCurrentMonth ? 'the current month' : selectedMonth}. Pending →
          Schedule. Scheduled → Mark done. Done → status.
        </p>
      </header>
      <WeeklyTrackerGrid year={period?.y} month={period?.m} />
    </div>
  );
}
