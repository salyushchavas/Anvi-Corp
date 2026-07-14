'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  AlertTriangle,
  CalendarPlus,
  CheckCircle2,
  ChevronLeft,
  Clock,
  ExternalLink,
  Info,
  Play,
  RefreshCw,
} from 'lucide-react';
import api from '@/lib/api';
import type { I983ListResponse, I983ListRow } from '@/components/evaluator/types';

type Tab = 'DUE_SOON' | 'IN_PROGRESS' | 'COMPLETED';

export default function I983EvaluationsPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>('DUE_SOON');
  const [data, setData] = useState<I983ListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<I983ListResponse>('/api/v1/evaluator/i983-evaluations');
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function startEval(id: string) {
    try {
      await api.post(`/api/v1/evaluator/i983-evaluations/${id}/start`);
      router.push(`/careers/evaluator/i983-evaluations/${id}/compose`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to start');
    }
  }

  const dueSoon = data?.dueSoon ?? [];
  const inProg = data?.inProgress ?? [];
  const completed = data?.completed ?? [];

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link href="/careers/evaluator" className="inline-flex items-center gap-1 hover:text-slate-700">
          <ChevronLeft className="h-3.5 w-3.5" />
          Evaluator home
        </Link>
      </p>

      <div className="rounded-lg border border-amber-200 bg-amber-50/30 p-4">
        <div className="flex items-start gap-2">
          <Info className="mt-0.5 h-4 w-4 text-amber-800" />
          <div>
            <p className="text-sm font-semibold text-amber-900">
              I-983 Evaluations — STEM OPT compliance
            </p>
            <p className="mt-1 text-xs text-amber-800">
              Federally required for STEM OPT students: Annual Review at the
              12-month mark, Final Review at the end of training. Each must be
              submitted to the student's DSO within 10 days of student
              acknowledgment.
            </p>
          </div>
        </div>
      </div>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-xl font-semibold text-slate-900">I-983 Evaluations</h1>
        <button
          type="button"
          onClick={() => void load()}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
      </div>

      <div className="flex gap-1 border-b border-slate-200">
        <TabBtn label="Due Soon" badge={dueSoon.length} active={tab === 'DUE_SOON'}
          onClick={() => setTab('DUE_SOON')} />
        <TabBtn label="In Progress" badge={inProg.length} active={tab === 'IN_PROGRESS'}
          onClick={() => setTab('IN_PROGRESS')} />
        <TabBtn label="Completed" badge={completed.length} active={tab === 'COMPLETED'}
          onClick={() => setTab('COMPLETED')} />
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-40 animate-pulse" />
        ) : tab === 'DUE_SOON' ? (
          <DueSoonTable rows={dueSoon} />
        ) : tab === 'IN_PROGRESS' ? (
          <InProgressTable rows={inProg} onStart={startEval} />
        ) : (
          <CompletedTable rows={completed} />
        )}
      </div>
    </div>
  );
}

function TabBtn({ label, badge, active, onClick }: {
  label: string; badge: number; active: boolean; onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        '-mb-px inline-flex items-center gap-2 border-b-2 px-3 py-2 text-sm ' +
        (active ? 'border-brand-700 font-semibold text-brand-700'
          : 'border-transparent text-slate-600 hover:text-slate-900')
      }
    >
      {label}
      <span className={
        'rounded-full px-1.5 py-0.5 text-[10px] font-semibold ' +
        (active ? 'bg-brand-100 text-brand-800' : 'bg-slate-100 text-slate-700')
      }>{badge}</span>
    </button>
  );
}

function DueSoonTable({ rows }: { rows: I983ListRow[] }) {
  if (rows.length === 0) {
    return (
      <p className="p-10 text-center text-sm text-slate-500">
        No STEM OPT interns are due for I-983 evaluation in the next 60 days.
      </p>
    );
  }
  return (
    <table className="min-w-full divide-y divide-slate-200 text-sm">
      <thead className="bg-slate-50">
        <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          <th className="px-3 py-2">Intern</th>
          <th className="px-3 py-2">Type</th>
          <th className="px-3 py-2">Training window</th>
          <th className="px-3 py-2">Next due</th>
          <th className="px-3 py-2 text-right"></th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {rows.map((r) => <DueSoonRow key={r.internLifecycleId} row={r} />)}
      </tbody>
    </table>
  );
}

function DueSoonRow({ row }: { row: I983ListRow }) {
  const days = row.daysUntilDue;
  const isUrgent = days != null && days <= 14;
  const isPast = days != null && days < 0;
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{row.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">{row.employeeId ?? '—'}</p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{row.evaluationType.replaceAll('_', ' ')}</td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.trainingStartDate ?? '—'}
        {row.trainingEndDate && ` → ${row.trainingEndDate}`}
      </td>
      <td className="px-3 py-2 text-xs">
        {!row.planExists ? (
          <span className="inline-flex items-center gap-0.5 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-800">
            <AlertTriangle className="h-3 w-3" />
            Plan not initiated
          </span>
        ) : row.nextDueDate ? (
          <span className={
            'inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 font-semibold ' +
            (isPast ? 'bg-red-100 text-red-700'
              : isUrgent ? 'bg-amber-100 text-amber-800'
                : 'bg-slate-100 text-slate-700')
          }>
            <Clock className="h-3 w-3" />
            {row.nextDueDate}{' '}
            ({isPast ? `${-days!}d past` : `${days}d`})
          </span>
        ) : '—'}
      </td>
      <td className="px-3 py-2 text-right">
        {row.planExists ? (
          <Link
            href={`/careers/evaluator/i983-evaluations/schedule?internId=${row.internLifecycleId}`}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
          >
            <CalendarPlus className="h-3 w-3" />
            Schedule
          </Link>
        ) : (
          <span className="text-[11px] text-slate-500">Contact ERM Compliance</span>
        )}
      </td>
    </tr>
  );
}

function InProgressTable({ rows, onStart }: {
  rows: I983ListRow[];
  onStart: (id: string) => void;
}) {
  if (rows.length === 0) {
    return (
      <p className="p-10 text-center text-sm text-slate-500">
        No I-983 evaluations in flight.
      </p>
    );
  }
  return (
    <table className="min-w-full divide-y divide-slate-200 text-sm">
      <thead className="bg-slate-50">
        <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          <th className="px-3 py-2">Intern</th>
          <th className="px-3 py-2">Type</th>
          <th className="px-3 py-2">Status</th>
          <th className="px-3 py-2">Published</th>
          <th className="px-3 py-2 text-right"></th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {rows.map((r) => (
          <tr key={r.evaluationId ?? r.internLifecycleId}>
            <td className="px-3 py-2">
              <p className="text-sm font-medium text-slate-900">{r.internName ?? '—'}</p>
              <p className="text-[11px] text-slate-500">{r.employeeId ?? '—'}</p>
            </td>
            <td className="px-3 py-2 text-xs text-slate-700">{r.evaluationType.replaceAll('_', ' ')}</td>
            <td className="px-3 py-2">
              <StatusPill status={r.status ?? '—'} />
            </td>
            <td className="px-3 py-2 text-xs text-slate-700">
              {r.publishedAt ? new Date(r.publishedAt).toLocaleDateString() : '—'}
            </td>
            <td className="px-3 py-2 text-right">
              {r.status === 'SCHEDULED' ? (
                <button
                  type="button"
                  onClick={() => r.evaluationId && onStart(r.evaluationId)}
                  className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
                >
                  <Play className="h-3 w-3" />
                  Start
                </button>
              ) : r.status === 'IN_PROGRESS' ? (
                <Link
                  href={`/careers/evaluator/i983-evaluations/${r.evaluationId}/compose`}
                  className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
                >
                  Continue
                </Link>
              ) : (
                <Link
                  href={`/careers/evaluator/i983-evaluations/${r.evaluationId}`}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2.5 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
                >
                  View
                </Link>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function CompletedTable({ rows }: { rows: I983ListRow[] }) {
  if (rows.length === 0) {
    return (
      <p className="p-10 text-center text-sm text-slate-500">
        No completed I-983 evaluations yet.
      </p>
    );
  }
  return (
    <table className="min-w-full divide-y divide-slate-200 text-sm">
      <thead className="bg-slate-50">
        <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          <th className="px-3 py-2">Intern</th>
          <th className="px-3 py-2">Type</th>
          <th className="px-3 py-2">Acknowledged</th>
          <th className="px-3 py-2">DSO submission</th>
          <th className="px-3 py-2 text-right"></th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {rows.map((r) => {
          const ackAt = r.acknowledgedAt ? new Date(r.acknowledgedAt) : null;
          const dsoPending = ackAt && !r.dsoSubmittedAt;
          const daysSinceAck = ackAt ? Math.floor((Date.now() - ackAt.getTime()) / 86_400_000) : null;
          const dsoUrgent = dsoPending && daysSinceAck != null && daysSinceAck > 7;
          return (
            <tr key={r.evaluationId ?? r.internLifecycleId}>
              <td className="px-3 py-2">
                <p className="text-sm font-medium text-slate-900">{r.internName ?? '—'}</p>
                <p className="text-[11px] text-slate-500">{r.employeeId ?? '—'}</p>
              </td>
              <td className="px-3 py-2 text-xs text-slate-700">{r.evaluationType.replaceAll('_', ' ')}</td>
              <td className="px-3 py-2 text-xs text-slate-700">
                {ackAt ? ackAt.toLocaleDateString() : '—'}
              </td>
              <td className="px-3 py-2 text-xs">
                {r.dsoSubmittedAt ? (
                  <span className="inline-flex items-center gap-0.5 rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-semibold text-green-700">
                    <CheckCircle2 className="h-3 w-3" />
                    {new Date(r.dsoSubmittedAt).toLocaleDateString()}
                  </span>
                ) : ackAt ? (
                  <span className={
                    'inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 font-semibold ' +
                    (dsoUrgent ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-800')
                  }>
                    <Clock className="h-3 w-3" />
                    Pending ({daysSinceAck}d)
                  </span>
                ) : (
                  <span className="text-slate-400">—</span>
                )}
              </td>
              <td className="px-3 py-2 text-right">
                <Link
                  href={`/careers/evaluator/i983-evaluations/${r.evaluationId}`}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2.5 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
                >
                  <ExternalLink className="h-3 w-3" />
                  View
                </Link>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function StatusPill({ status }: { status: string }) {
  const tone = status === 'PUBLISHED' ? 'bg-amber-100 text-amber-800'
    : status === 'IN_PROGRESS' ? 'bg-slate-100 text-slate-700'
    : status === 'SCHEDULED' ? 'bg-slate-100 text-slate-700'
    : status === 'AMENDED' ? 'bg-amber-100 text-amber-800'
    : 'bg-slate-100 text-slate-700';
  return <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${tone}`}>{status}</span>;
}
