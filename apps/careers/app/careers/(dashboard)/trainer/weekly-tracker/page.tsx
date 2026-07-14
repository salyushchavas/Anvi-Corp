'use client';

import WeeklyTrackerGrid from '@/components/trainer/weeklyTracker/WeeklyTrackerGrid';

export default function TrainerWeeklyTrackerPage() {
  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">
          Weekly Sessions
        </h1>
        <p className="text-xs text-slate-500">
          Status of every active intern&rsquo;s weekly session for the current
          month. Pending → Schedule. Scheduled → Mark done. Done → status.
        </p>
      </header>
      <WeeklyTrackerGrid />
    </div>
  );
}
