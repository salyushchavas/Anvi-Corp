'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { AlertTriangle, Clock, Eye, Play, RefreshCw, Video } from 'lucide-react';
import api from '@/lib/api';
import type {
  AwaitingAckRow,
  PendingEvaluationsResponse,
  ScheduledRow,
} from '@/components/evaluator/types';

type Tab = 'SCHEDULED' | 'AWAITING_ACK';

export default function PendingEvaluationsPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>('SCHEDULED');
  const [data, setData] = useState<PendingEvaluationsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<PendingEvaluationsResponse>(
        '/api/v1/evaluator/pending-evaluations',
      );
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

  async function startSession(id: string) {
    try {
      await api.post(`/api/v1/evaluator/evaluations/${id}/start`);
      router.push(`/careers/evaluator/evaluations/${id}/compose`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to start');
    }
  }

  const scheduled = data?.scheduledAndInProgress ?? [];
  const awaiting = data?.awaitingAcknowledgment ?? [];

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/evaluator" className="hover:text-slate-700">← Evaluator home</Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Pending Evaluations</h1>
          <p className="text-xs text-slate-500">
            Track scheduled sessions and published evaluations waiting on intern acknowledgment.
          </p>
        </div>
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
        <TabButton active={tab === 'SCHEDULED'} onClick={() => setTab('SCHEDULED')}
          label="Scheduled & In Progress" badge={scheduled.length} />
        <TabButton active={tab === 'AWAITING_ACK'} onClick={() => setTab('AWAITING_ACK')}
          label="Awaiting Intern Acknowledgment" badge={awaiting.length} />
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-40 animate-pulse" />
        ) : tab === 'SCHEDULED' ? (
          scheduled.length === 0 ? (
            <p className="p-10 text-center text-sm text-slate-500">
              No scheduled or in-progress sessions. Use Schedule Session to book one.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="px-3 py-2">Intern</th>
                  <th className="px-3 py-2">Type</th>
                  <th className="px-3 py-2">Scheduled for</th>
                  <th className="px-3 py-2">Status</th>
                  <th className="px-3 py-2 text-right"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {scheduled.map((r) => <ScheduledRowEl key={r.evaluationId} row={r} onStart={() => startSession(r.evaluationId)} />)}
              </tbody>
            </table>
          )
        ) : (
          awaiting.length === 0 ? (
            <p className="p-10 text-center text-sm text-slate-500">
              All published evaluations are acknowledged. Nice.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="px-3 py-2">Intern</th>
                  <th className="px-3 py-2">Type</th>
                  <th className="px-3 py-2">Published</th>
                  <th className="px-3 py-2">Pending</th>
                  <th className="px-3 py-2 text-right"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {awaiting.map((r) => <AwaitingRowEl key={r.evaluationId} row={r} />)}
              </tbody>
            </table>
          )
        )}
      </div>
    </div>
  );
}

function TabButton({ active, onClick, label, badge }: {
  active: boolean; onClick: () => void; label: string; badge: number;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        '-mb-px inline-flex items-center gap-2 border-b-2 px-3 py-2 text-sm ' +
        (active
          ? 'border-brand-700 font-semibold text-brand-700'
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

function ScheduledRowEl({ row, onStart }: { row: ScheduledRow; onStart: () => void }) {
  const when = row.scheduledFor ? new Date(row.scheduledFor) : null;
  const startable = row.status === 'SCHEDULED' && when
    && when.getTime() - Date.now() < 2 * 3600_000  // within 2 hours
    && Date.now() - when.getTime() < 24 * 3600_000; // not > 1 day past
  const inProgress = row.status === 'IN_PROGRESS';
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{row.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">{row.employeeId ?? '—'}</p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{row.evaluationType}</td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {when ? when.toLocaleString() : '—'}
        {row.durationMinutes && <span className="ml-1 text-slate-500">· {row.durationMinutes}m</span>}
        {row.zoomJoinUrl && (
          <a
            href={row.zoomJoinUrl}
            target="_blank"
            rel="noreferrer"
            className="ml-2 inline-flex items-center gap-0.5 text-[11px] font-medium text-brand-700 hover:underline"
          >
            <Video className="h-3 w-3" /> Join
          </a>
        )}
      </td>
      <td className="px-3 py-2">
        <span className={
          'inline-flex rounded-full px-2 py-0.5 text-[10px] font-semibold ' +
          (inProgress ? 'bg-amber-100 text-amber-800' : 'bg-slate-100 text-slate-700')
        }>{row.status}</span>
      </td>
      <td className="px-3 py-2 text-right">
        {inProgress ? (
          <Link
            href={`/careers/evaluator/evaluations/${row.evaluationId}/compose`}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
          >
            Continue
          </Link>
        ) : (
          <button
            type="button"
            onClick={onStart}
            disabled={!startable && !inProgress}
            title={!startable ? 'Available within 2 hours of scheduled time' : ''}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
          >
            <Play className="h-3 w-3" />
            Start
          </button>
        )}
      </td>
    </tr>
  );
}

function AwaitingRowEl({ row }: { row: AwaitingAckRow }) {
  const urgent = row.daysPending > 7;
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{row.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">{row.employeeId ?? '—'}</p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{row.evaluationType}</td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.publishedAt ? new Date(row.publishedAt).toLocaleDateString() : '—'}
      </td>
      <td className="px-3 py-2 text-xs">
        <span className={
          'inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 font-semibold ' +
          (urgent ? 'bg-red-100 text-red-700' : 'bg-slate-100 text-slate-700')
        }>
          {urgent ? <AlertTriangle className="h-3 w-3" /> : <Clock className="h-3 w-3" />}
          {row.daysPending}d
        </span>
      </td>
      <td className="px-3 py-2 text-right">
        <Link
          href={`/careers/evaluator/evaluations/${row.evaluationId}`}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2.5 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
        >
          <Eye className="h-3 w-3" />
          View
        </Link>
      </td>
    </tr>
  );
}
