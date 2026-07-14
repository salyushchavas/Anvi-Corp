'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import InterviewStatusPill from '@/components/erm/interviews/InterviewStatusPill';
import DecisionPill from '@/components/erm/interviews/DecisionPill';
import { formatInZone } from '@/lib/format-interview-time';
import type {
  InterviewListPage,
  InterviewRow,
} from '@/components/erm/interviews/types';

const STATUS_CHIPS: { key: string; label: string }[] = [
  { key: '', label: 'All' },
  { key: 'SCHEDULED', label: 'Scheduled' },
  { key: 'COMPLETED', label: 'Completed' },
  { key: 'CANCELLED', label: 'Cancelled' },
  { key: 'NO_SHOW', label: 'No-show' },
];

export default function InterviewSchedulerPage() {
  const [view, setView] = useState<'list' | 'calendar'>('list');
  const [status, setStatus] = useState('');
  const [scope, setScope] = useState<'mine' | 'all'>('mine');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<InterviewListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (status) params.set('status', status);
      if (search.trim()) params.set('search', search.trim());
      params.set('scope', scope);
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<InterviewListPage>(
        `/api/v1/erm/interviews?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load interviews');
    } finally {
      setLoading(false);
    }
  }, [status, search, scope, page]);

  useEffect(() => { void load(); }, [load]);

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <div className="flex items-center justify-between gap-3">
          <PageHeader
            title="Interview Scheduler"
            subtitle="Calendar + queue view of every interview."
          />
          <div className="inline-flex overflow-hidden rounded-md border border-slate-200">
            {(['list', 'calendar'] as const).map((v) => (
              <button
                key={v}
                type="button"
                onClick={() => setView(v)}
                className={
                  'px-3 py-1.5 text-xs font-medium ' +
                  (view === v
                    ? 'bg-brand-700 text-white'
                    : 'bg-white text-slate-700 hover:bg-slate-50')
                }
              >
                {v === 'list' ? 'List' : 'Calendar'}
              </button>
            ))}
          </div>
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          {STATUS_CHIPS.map((c) => (
            <button
              key={c.key || 'all'}
              type="button"
              onClick={() => { setStatus(c.key); setPage(0); }}
              className={
                'rounded-full border px-3 py-1 text-xs font-medium ' +
                (status === c.key
                  ? 'border-brand-700 bg-brand-700 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {c.label}
            </button>
          ))}
          <div className="ml-auto flex items-center gap-2">
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { setPage(0); void load(); } }}
              placeholder="Search applicant"
              className="w-56 rounded-md border border-slate-200 px-3 py-1.5 text-sm"
            />
            <div className="inline-flex overflow-hidden rounded-md border border-slate-200 text-xs">
              {(['mine', 'all'] as const).map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => { setScope(s); setPage(0); }}
                  className={
                    'px-3 py-1.5 font-medium ' +
                    (scope === s
                      ? 'bg-brand-700 text-white'
                      : 'bg-white text-slate-700 hover:bg-slate-50')
                  }
                >
                  {s === 'mine' ? 'Mine' : 'All'}
                </button>
              ))}
            </div>
          </div>
        </div>

        {err && (
          <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        {view === 'list' && (
          <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
            {loading && !data ? (
              <div className="h-40 animate-pulse" />
            ) : !data || data.items.length === 0 ? (
              <p className="p-10 text-center text-sm text-slate-500">
                No interviews match the current filters.
              </p>
            ) : (
              <table className="min-w-full divide-y divide-slate-200 text-sm">
                <thead className="bg-slate-50">
                  <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                    <th className="px-3 py-2">Applicant</th>
                    <th className="px-3 py-2">Job</th>
                    <th className="px-3 py-2">Scheduled</th>
                    <th className="px-3 py-2">Interviewer</th>
                    <th className="px-3 py-2">Status</th>
                    <th className="px-3 py-2">Decision</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.items.map((r) => <Row key={r.interviewId} row={r} />)}
                </tbody>
              </table>
            )}
          </div>
        )}

        {view === 'calendar' && <CalendarView />}

        {data && data.totalPages > 1 && view === 'list' && (
          <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
            <span>
              Page {data.page + 1} of {data.totalPages} ({data.totalElements} total)
            </span>
            <div className="flex gap-1">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50"
              >
                Prev
              </button>
              <button
                type="button"
                disabled={data.page + 1 >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Row({ row }: { row: InterviewRow }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <Link
          href={`/careers/erm/interviews/${row.interviewId}`}
          className="hover:underline"
        >
          <span className="block text-sm font-medium text-slate-900">
            {row.applicantName ?? '(unknown)'}
          </span>
          <span className="block text-[11px] text-slate-500">{row.applicantId}</span>
        </Link>
      </td>
      <td className="px-3 py-2">
        <span className="block text-sm text-slate-800">{row.jobTitle}</span>
        <span className="block text-[11px] text-slate-500">{row.jobType}</span>
      </td>
      <td className="px-3 py-2 text-xs">
        <span className="block">{formatInZone(row.scheduledAt, row.timezone)}</span>
        <span className="block text-[11px] text-slate-500">
          {row.durationMinutes ?? 60} min
        </span>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.interviewerName ?? '—'}
      </td>
      <td className="px-3 py-2">
        <InterviewStatusPill status={row.status} />
        {row.rescheduleCount > 0 && (
          <span className="ml-2 inline-block rounded-full bg-slate-100 px-2 py-0.5 text-[10px] text-slate-600">
            rescheduled {row.rescheduleCount}×
          </span>
        )}
      </td>
      <td className="px-3 py-2">
        <DecisionPill decision={row.decision} />
      </td>
    </tr>
  );
}

interface CalendarEntryRow {
  interviewId: string;
  applicantName: string | null;
  jobTitle: string | null;
  scheduledAt: string;
  durationMinutes: number | null;
  timezone: string | null;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
  decision: string | null;
  interviewerName: string | null;
}

function CalendarView() {
  const [entries, setEntries] = useState<CalendarEntryRow[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      setLoading(true);
      try {
        const start = new Date();
        start.setHours(0, 0, 0, 0);
        const end = new Date(start);
        end.setDate(end.getDate() + 30);
        const res = await api.get<CalendarEntryRow[]>(
          `/api/v1/erm/interviews/calendar?from=${start.toISOString()}&to=${end.toISOString()}`,
        );
        setEntries(res.data ?? []);
      } catch (e) {
        setErr(e instanceof Error ? e.message : 'Calendar load failed');
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  if (loading) return <div className="h-32 animate-pulse rounded-lg bg-slate-50" />;
  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (!entries || entries.length === 0) {
    return (
      <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
        No interviews in the next 30 days.
      </p>
    );
  }
  const grouped = new Map<string, CalendarEntryRow[]>();
  for (const e of entries) {
    const day = new Date(e.scheduledAt).toLocaleDateString();
    const arr = grouped.get(day) ?? [];
    arr.push(e);
    grouped.set(day, arr);
  }
  return (
    <div className="space-y-4">
      {Array.from(grouped.entries()).map(([day, items]) => (
        <section
          key={day}
          className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
        >
          <h3 className="text-sm font-semibold text-slate-900">{day}</h3>
          <ul className="mt-2 space-y-2">
            {items.map((e) => (
              <li
                key={e.interviewId}
                className="flex items-center justify-between gap-3 rounded-md border border-slate-100 bg-slate-50 p-3"
              >
                <div>
                  <Link
                    href={`/careers/erm/interviews/${e.interviewId}`}
                    className="text-sm font-medium text-slate-900 hover:underline"
                  >
                    {formatInZone(e.scheduledAt, e.timezone)} · {e.applicantName ?? '(unknown)'}
                  </Link>
                  <p className="text-[11px] text-slate-500">
                    {e.jobTitle} · {e.interviewerName ?? 'no interviewer'}
                  </p>
                </div>
                <InterviewStatusPill status={e.status} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
