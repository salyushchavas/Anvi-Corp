'use client';

import Link from 'next/link';
import { Video } from 'lucide-react';
import { useErmDashboard } from './ErmDashboardContext';

/**
 * Phase 1 — Home page "Today's interviews" card. Sources the count from
 * the right-panel response (already polled); a richer per-interview list
 * lands on the Interviews page in Phase 2.
 */
export default function TodayInterviewsCard() {
  const { rightPanel } = useErmDashboard();
  const count = rightPanel?.todayInterviewsCount ?? 0;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">
          Today&apos;s interviews
        </h3>
        <Link
          href="/careers/erm/interviews"
          className="text-xs font-medium text-brand-700 hover:underline"
        >
          Open queue →
        </Link>
      </div>
      <div className="mt-4 flex items-center gap-3">
        <Video className="h-6 w-6 text-brand-700" strokeWidth={2} />
        <div>
          <p className="text-3xl font-semibold tabular-nums text-slate-900">
            {count}
          </p>
          <p className="text-xs text-slate-500">
            {count === 0
              ? 'Nothing on the calendar today.'
              : count === 1
                ? '1 interview scheduled today'
                : `${count} interviews scheduled today`}
          </p>
        </div>
      </div>
    </section>
  );
}
