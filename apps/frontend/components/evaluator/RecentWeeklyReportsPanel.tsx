'use client';

import { useEffect, useState } from 'react';
import { FileText } from 'lucide-react';
import api from '@/lib/careers/api';
import type { WeeklyReportsResponse } from './perproject-types';

/**
 * Read-only panel showing the intern's recent weekly reports, embedded
 * on the evaluator's compose hub. Reuses the existing per-intern weekly
 * report fetch — we deliberately don't filter by project-date window
 * here (recent reports give enough context; the eval matrix already
 * carries the project-specific evidence).
 */
interface Props {
  lifecycleId: string | null | undefined;
  limit?: number;
}

export default function RecentWeeklyReportsPanel({ lifecycleId, limit = 5 }: Props) {
  const [data, setData] = useState<WeeklyReportsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!lifecycleId) return;
    let cancelled = false;
    setLoading(true);
    setErr(null);
    api.get<WeeklyReportsResponse>(
      `/api/v1/evaluator/evaluees/${lifecycleId}/weekly-reports?limit=${limit}`,
    )
      .then((res) => { if (!cancelled) setData(res.data); })
      .catch((e) => {
        if (cancelled) return;
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load weekly reports');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [lifecycleId, limit]);

  if (!lifecycleId) return null;
  if (loading && !data) {
    return <div className="h-24 animate-pulse rounded-lg bg-slate-100" aria-hidden />;
  }
  if (err) {
    return <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>;
  }
  const items = data?.items ?? [];
  if (items.length === 0) return null;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center gap-2">
        <FileText className="h-4 w-4 text-brand-700" />
        <h2 className="text-sm font-semibold text-slate-900">
          Recent weekly reports
        </h2>
        <span className="text-[11px] text-slate-500">({items.length})</span>
      </div>
      <p className="mt-0.5 text-[11px] text-slate-500">
        Intern's own account of what they built each week — context for the
        evaluation.
      </p>
      <ol className="mt-3 space-y-2">
        {items.map((r) => (
          <li key={r.reportId} className="rounded-md border border-slate-200 bg-slate-50 p-3">
            <div className="flex items-center justify-between gap-2">
              <p className="text-xs font-semibold text-slate-800">
                Week of {r.weekStart ? new Date(r.weekStart).toLocaleDateString() : '—'}
              </p>
              {r.status && (
                <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
                  {r.status}
                </span>
              )}
            </div>
            {r.completedWork && (
              <div className="mt-2">
                <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                  Completed
                </p>
                <p className="mt-0.5 whitespace-pre-wrap text-xs text-slate-700">
                  {r.completedWork}
                </p>
              </div>
            )}
            {r.blockers && (
              <div className="mt-2">
                <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                  Blockers
                </p>
                <p className="mt-0.5 whitespace-pre-wrap text-xs text-slate-700">
                  {r.blockers}
                </p>
              </div>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}
