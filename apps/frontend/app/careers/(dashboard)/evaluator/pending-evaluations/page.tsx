'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { AlertTriangle, Clock, Eye, Play, RefreshCw, Video } from 'lucide-react';
import api from '@/lib/careers/api';
import { useEvaluatorDashboard } from '@/components/evaluator/EvaluatorDashboardContext';
import type {
  AwaitingAckRow,
  PendingEvaluationsResponse,
  ScheduledRow,
} from '@/components/evaluator/types';

type Tab = 'SCHEDULED' | 'AWAITING_ACK';

export default function PendingEvaluationsPage() {
  const router = useRouter();
  const { selectedMonth, isCurrentMonth } = useEvaluatorDashboard();
  const [tab, setTab] = useState<Tab>('SCHEDULED');
  const [data, setData] = useState<PendingEvaluationsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const url = isCurrentMonth
        ? '/api/v1/evaluator/pending-evaluations'
        : `/api/v1/evaluator/pending-evaluations?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<PendingEvaluationsResponse>(url);
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [selectedMonth, isCurrentMonth]);
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

  async function startSessionGroup(sessionGroupId: string, fallbackEvalId: string) {
    try {
      const res = await api.post<{ startedEvaluationIds: string[] }>(
        `/api/v1/evaluator/session-groups/${sessionGroupId}/start`);
      const first = res.data.startedEvaluationIds?.[0] ?? fallbackEvalId;
      router.push(`/careers/evaluator/evaluations/${first}/compose`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to start session');
    }
  }

  const scheduled = data?.scheduledAndInProgress ?? [];
  const awaiting = data?.awaitingAcknowledgment ?? [];

  // Collapse rows sharing a session_group_id into one entry per group
  // ("one session covering N projects"). Rows with no group id, or a
  // group-of-one, render as before. Preserves the server's ordering by
  // keying entries on the first-seen member's index.
  const scheduledEntries = useMemo(() => {
    type Entry =
      | { kind: 'single'; row: ScheduledRow }
      | { kind: 'group'; groupId: string; members: ScheduledRow[] };
    const seenIndex = new Map<string, number>();
    const groups = new Map<string, ScheduledRow[]>();
    scheduled.forEach((row, idx) => {
      if (!row.sessionGroupId) return;
      if (!seenIndex.has(row.sessionGroupId)) seenIndex.set(row.sessionGroupId, idx);
      const list = groups.get(row.sessionGroupId) ?? [];
      list.push(row);
      groups.set(row.sessionGroupId, list);
    });
    const out: Array<{ orderIdx: number; entry: Entry }> = [];
    const emittedGroups = new Set<string>();
    scheduled.forEach((row, idx) => {
      if (!row.sessionGroupId) {
        out.push({ orderIdx: idx, entry: { kind: 'single', row } });
        return;
      }
      const gid = row.sessionGroupId;
      const members = groups.get(gid) ?? [row];
      if (members.length < 2) {
        // Group-of-one collapses back to the single-row render.
        out.push({ orderIdx: idx, entry: { kind: 'single', row } });
        return;
      }
      if (emittedGroups.has(gid)) return;
      emittedGroups.add(gid);
      out.push({
        orderIdx: seenIndex.get(gid) ?? idx,
        entry: { kind: 'group', groupId: gid, members },
      });
    });
    return out.sort((a, b) => a.orderIdx - b.orderIdx).map((e) => e.entry);
  }, [scheduled]);

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/evaluator" className="hover:text-slate-700">← Evaluator home</Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Scheduled Evaluations</h1>
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
          scheduledEntries.length === 0 ? (
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
                {scheduledEntries.map((entry) => (
                  entry.kind === 'single'
                    ? <ScheduledRowEl
                        key={entry.row.evaluationId}
                        row={entry.row}
                        onStart={() => startSession(entry.row.evaluationId)}
                      />
                    : <SessionGroupRowEl
                        key={entry.groupId}
                        groupId={entry.groupId}
                        members={entry.members}
                        onStart={() => startSessionGroup(entry.groupId, entry.members[0]!.evaluationId)}
                      />
                ))}
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

/**
 * Collapsed row rendering for a multi-project shared session. One
 * datetime, one Start (or Continue when live), and a project-label
 * stack so the operator sees "one session covering P1 + P2" at a
 * glance instead of two identical rows racing for two clicks.
 */
function SessionGroupRowEl({
  groupId, members, onStart,
}: {
  groupId: string;
  members: ScheduledRow[];
  onStart: () => void;
}) {
  void groupId;
  const first = members[0]!;
  const when = first.scheduledFor ? new Date(first.scheduledFor) : null;
  const anyInProgress = members.some((m) => m.status === 'IN_PROGRESS');
  const startable = !anyInProgress && when
    && when.getTime() - Date.now() < 2 * 3600_000
    && Date.now() - when.getTime() < 24 * 3600_000;
  const projectLabels = members
    .map((m, i) => m.linkedProjectTitle ?? `Project ${i + 1}`);
  const anyTerminal = members.some((m) =>
    m.status === 'PUBLISHED' || m.status === 'ACKNOWLEDGED' || m.status === 'AMENDED');

  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{first.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">{first.employeeId ?? '—'}</p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
          One session · {members.length} projects
        </span>
        <p className="mt-0.5 text-[11px] text-slate-500">{projectLabels.join(' + ')}</p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {when ? when.toLocaleString() : '—'}
        {first.durationMinutes && <span className="ml-1 text-slate-500">· {first.durationMinutes}m</span>}
        {first.zoomJoinUrl && (
          <a
            href={first.zoomJoinUrl}
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
          (anyInProgress ? 'bg-amber-100 text-amber-800' : 'bg-slate-100 text-slate-700')
        }>
          {anyInProgress ? 'In progress' : 'Scheduled'}
        </span>
      </td>
      <td className="px-3 py-2 text-right">
        {anyInProgress ? (
          <Link
            href={`/careers/evaluator/evaluations/${first.evaluationId}/compose`}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
          >
            Continue
          </Link>
        ) : anyTerminal ? (
          <Link
            href={`/careers/evaluator/evaluations/${first.evaluationId}`}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2.5 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
          >
            <Eye className="h-3 w-3" />
            Open
          </Link>
        ) : (
          <button
            type="button"
            onClick={onStart}
            disabled={!startable}
            title={!startable ? 'Available within 2 hours of scheduled time' : ''}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
          >
            <Play className="h-3 w-3" />
            Start session
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
