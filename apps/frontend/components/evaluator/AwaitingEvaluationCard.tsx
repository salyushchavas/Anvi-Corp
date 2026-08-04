'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { AlertCircle, ClipboardList, RefreshCw, TimerReset } from 'lucide-react';
import api from '@/lib/careers/api';
import type { AwaitingEvaluationResponse, AwaitingEvaluationRow } from './perproject-types';
import { useEvaluatorDashboard } from './EvaluatorDashboardContext';

/**
 * Evaluator's "Projects Awaiting Evaluation" worklist.
 *
 * <p>Polls {@code GET /api/v1/evaluator/pending-post-project-evaluations},
 * shows oldest-first (server sorts by project.completedAt ASC), and
 * links each row to the compose screen — which the prior task already
 * wired to surface project + Q&A + recording + weekly reports in one
 * context hub.</p>
 */
interface Props {
  /** Max rows to render inline. Extra rows collapse under a "+N more" tail. */
  limit?: number;
  /** Show the header (default true — hide it when embedded in a page that
   *  already has one). */
  showHeader?: boolean;
}

export default function AwaitingEvaluationCard({ limit = 6, showHeader = true }: Props) {
  const { selectedMonth, isCurrentMonth } = useEvaluatorDashboard();
  const [data, setData] = useState<AwaitingEvaluationResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      // Sticky month scope — for a past month, restrict to projects
      // whose completed_at (or scheduled_for) falls in that month.
      // Current month omits the param so the byte-identical prior
      // queue comes back.
      const url = isCurrentMonth
        ? '/api/v1/evaluator/pending-post-project-evaluations'
        : `/api/v1/evaluator/pending-post-project-evaluations?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<AwaitingEvaluationResponse>(url);
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [selectedMonth, isCurrentMonth]);
  useEffect(() => { void load(); }, [load]);

  const shown = useMemo(
    () => (data?.items ?? []).slice(0, limit),
    [data, limit],
  );
  const overflow = Math.max(0, (data?.totalCount ?? 0) - shown.length);
  const total = data?.totalCount ?? 0;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      {showHeader && (
        <div className="mb-3 flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <ClipboardList className="h-4 w-4 text-brand-700" />
              <h2 className="text-sm font-semibold text-slate-900">
                Projects Awaiting Evaluation
              </h2>
              {total > 0 && (
                <span className="rounded-full bg-brand-100 px-2 py-0.5 text-[10px] font-semibold text-brand-800">
                  {total}
                </span>
              )}
            </div>
            <p className="mt-0.5 text-[11px] text-slate-500">
              Completed projects with an open evaluation. Oldest first.
            </p>
          </div>
          <button type="button" onClick={() => void load()}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-600 hover:bg-slate-50">
            <RefreshCw className="h-3 w-3" /> Refresh
          </button>
        </div>
      )}

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}
      {loading && !data && (
        <div className="h-20 animate-pulse rounded-md bg-slate-100" aria-hidden />
      )}
      {!loading && total === 0 && (
        <p className="rounded-md border border-dashed border-slate-300 p-4 text-center text-xs text-slate-500">
          Nothing waiting — every completed project has been evaluated.
        </p>
      )}
      {shown.length > 0 && (
        <ul className="divide-y divide-slate-100">
          {shown.map((row) => <Row key={row.evaluationId} row={row} />)}
        </ul>
      )}
      {overflow > 0 && (
        <p className="mt-2 text-[11px] text-slate-500">
          +{overflow} more · open the queue to see them all.
        </p>
      )}
    </section>
  );
}

function Row({ row }: { row: AwaitingEvaluationRow }) {
  const href = `/careers/evaluator/evaluations/${row.evaluationId}/compose`;
  const daysWaiting = row.hoursWaitingSinceCompletion != null
    ? Math.floor(row.hoursWaitingSinceCompletion / 24)
    : null;
  const urgent = (daysWaiting ?? 0) >= 3;
  return (
    <li className="py-2">
      <Link href={href} className="group flex items-start justify-between gap-3 rounded-md px-1 py-1 hover:bg-slate-50">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate text-sm font-medium text-slate-900">
              {row.internName ?? '(unnamed intern)'}
            </p>
            <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
              #{row.projectSequence}
            </span>
            {row.evaluationStatus === 'IN_PROGRESS' && (
              <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800">
                In progress
              </span>
            )}
            {row.evaluationStatus === 'SCHEDULED' && (
              <span className="rounded bg-brand-100 px-1.5 py-0.5 text-[10px] font-semibold text-brand-800">
                Scheduled
              </span>
            )}
          </div>
          <p className="mt-0.5 truncate text-xs text-slate-600">
            {row.projectTitle ?? '(untitled project)'}
            {row.techStack && (
              <span className="ml-1 text-slate-400">· {row.techStack}</span>
            )}
          </p>
          <p className="mt-0.5 flex items-center gap-1 text-[11px] text-slate-500">
            <TimerReset className="h-3 w-3" />
            {daysWaiting != null
              ? `Completed ${daysWaiting}d ago`
              : 'Awaiting completion timestamp'}
            {urgent && (
              <span className="ml-1 inline-flex items-center gap-0.5 rounded-full bg-red-100 px-1.5 py-0.5 text-[10px] font-semibold text-red-700">
                <AlertCircle className="h-2.5 w-2.5" />
                Overdue
              </span>
            )}
          </p>
        </div>
        <span className="shrink-0 rounded-md bg-brand-700 px-2.5 py-1 text-[11px] font-semibold text-white group-hover:bg-brand-800">
          Evaluate
        </span>
      </Link>
    </li>
  );
}
