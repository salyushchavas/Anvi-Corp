'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { CalendarCheck2 } from 'lucide-react';
import api from '@/lib/api';
import type { TrackerResponse } from './WeeklyTrackerGrid';

/**
 * Compact summary card for the trainer dashboard. Pulls the same
 * tracker payload the master page uses, but renders only the totals +
 * an interns-with-pending-weeks count, with a "View tracker →" link.
 */
export default function WeeklyTrackerSummaryCard() {
  const [data, setData] = useState<TrackerResponse | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.get<TrackerResponse>('/api/v1/trainer/weekly-tracker')
      .then((r) => { if (!cancelled) setData(r.data); })
      .catch((e) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'load failed');
      });
    return () => { cancelled = true; };
  }, []);

  const totals = data?.interns.reduce(
    (acc, r) => ({
      done: acc.done + r.doneCount,
      scheduled: acc.scheduled + r.scheduledCount,
      pending: acc.pending + r.pendingCount,
      missed: acc.missed + r.missedCount,
    }),
    { done: 0, scheduled: 0, pending: 0, missed: 0 },
  );
  const internsWithPending = (data?.interns ?? [])
    .filter((r) => r.pendingCount > 0 || r.missedCount > 0).length;

  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <div className="flex items-center gap-2">
          <CalendarCheck2 className="h-4 w-4 text-brand-600" strokeWidth={2} />
          <h3 className="text-sm font-semibold text-slate-900">
            Weekly Sessions — this month
          </h3>
        </div>
        {data && (
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-600">
            {data.interns.length} intern{data.interns.length === 1 ? '' : 's'}
          </span>
        )}
      </header>
      {err && (
        <p className="px-4 py-3 text-xs text-red-700">{err}</p>
      )}
      {!err && !data ? (
        <div className="px-4 py-6">
          <div className="h-4 w-full animate-pulse rounded bg-slate-100" />
        </div>
      ) : data && data.interns.length === 0 ? (
        <div className="px-4 py-6 text-center text-xs text-slate-500">
          No active interns in your roster yet.
        </div>
      ) : data && totals && (
        <div className="px-4 py-3">
          <div className="flex flex-wrap gap-1.5 text-[11px]">
            {totals.done > 0 && (
              <span className="rounded-full bg-green-100 px-2 py-0.5 font-semibold text-green-800">
                {totals.done} done
              </span>
            )}
            {totals.scheduled > 0 && (
              <span className="rounded-full bg-brand-100 px-2 py-0.5 font-semibold text-brand-800">
                {totals.scheduled} scheduled
              </span>
            )}
            {totals.pending > 0 && (
              <span className="rounded-full bg-amber-100 px-2 py-0.5 font-semibold text-amber-900">
                {totals.pending} to schedule
              </span>
            )}
            {totals.missed > 0 && (
              <span className="rounded-full bg-red-100 px-2 py-0.5 font-semibold text-red-800">
                {totals.missed} missed
              </span>
            )}
          </div>
          {internsWithPending > 0 && (
            <p className="mt-2 text-xs text-slate-600">
              {internsWithPending} intern{internsWithPending === 1 ? '' : 's'}{' '}
              need attention.
            </p>
          )}
        </div>
      )}
      <footer className="border-t border-slate-100 px-4 py-2 text-right">
        <Link
          href="/careers/trainer/weekly-tracker"
          className="text-[11px] font-medium text-brand-700 hover:underline"
        >
          View tracker →
        </Link>
      </footer>
    </section>
  );
}
