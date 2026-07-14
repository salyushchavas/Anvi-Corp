'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import SeverityBadge from '@/components/erm/escalations/SeverityBadge';
import StatusPill from '@/components/erm/escalations/StatusPill';
import {
  EXCEPTION_TYPE_LABEL,
  type ExceptionListPage,
  type ExceptionRow,
  type ExceptionStatus,
  type Severity,
} from '@/components/erm/escalations/types';

const STATUS_TABS: { key: ExceptionStatus | ''; label: string }[] = [
  { key: '', label: 'All' },
  { key: 'OPEN', label: 'Open' },
  { key: 'ASSIGNED', label: 'Assigned' },
  { key: 'IN_PROGRESS', label: 'In progress' },
  { key: 'RESOLVED', label: 'Resolved' },
  { key: 'DISMISSED', label: 'Dismissed' },
  { key: 'AUTO_RESOLVED', label: 'Auto-resolved' },
];

const SEVERITY_TABS: { key: Severity | ''; label: string }[] = [
  { key: '', label: 'All severity' },
  { key: 'URGENT', label: 'Urgent' },
  { key: 'WARN', label: 'Warn' },
  { key: 'INFO', label: 'Info' },
];

const POLL_MS = 60_000;

export default function EscalationsPage() {
  const [status, setStatus] = useState<ExceptionStatus | ''>('OPEN');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [search, setSearch] = useState('');
  const [scope, setScope] = useState<'all' | 'mine'>('all');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ExceptionListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (status) params.append('status', status);
      if (severity) params.append('severity', severity);
      if (search.trim()) params.set('search', search.trim());
      params.set('scope', scope);
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<ExceptionListPage>(
        `/api/v1/erm/escalations?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [status, severity, search, scope, page]);

  useEffect(() => {
    void load();
    const id = setInterval(() => {
      void load();
    }, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Escalations"
          subtitle="Cross-intern operational queue. Auto-detected by the 15-min scan job."
        />

        <div className="mb-3 flex flex-wrap items-center gap-2">
          <ScopeToggle scope={scope} onChange={setScope} />
          <span className="ml-2 text-[11px] uppercase text-slate-400">Status</span>
          {STATUS_TABS.map((c) => (
            <button
              key={c.key || 'all'}
              type="button"
              onClick={() => {
                setStatus(c.key);
                setPage(0);
              }}
              className={
                'rounded-full border px-2.5 py-0.5 text-xs font-medium ' +
                (status === c.key
                  ? 'border-brand-700 bg-brand-700 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {c.label}
            </button>
          ))}
          <span className="ml-2 text-[11px] uppercase text-slate-400">Severity</span>
          {SEVERITY_TABS.map((c) => (
            <button
              key={c.key || 'sev-all'}
              type="button"
              onClick={() => {
                setSeverity(c.key);
                setPage(0);
              }}
              className={
                'rounded-full border px-2.5 py-0.5 text-xs font-medium ' +
                (severity === c.key
                  ? 'border-red-600 bg-red-600 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {c.label}
            </button>
          ))}
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setPage(0);
                void load();
              }
            }}
            placeholder="Search intern"
            className="ml-auto w-56 rounded-md border border-slate-200 px-3 py-1.5 text-sm"
          />
        </div>

        {err && (
          <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          {loading && !data ? (
            <div className="h-40 animate-pulse" />
          ) : !data || data.items.length === 0 ? (
            <p className="p-10 text-center text-sm text-slate-500">
              No exceptions match the current filters.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="px-3 py-2">Sev</th>
                  <th className="px-3 py-2">Type</th>
                  <th className="px-3 py-2">Intern</th>
                  <th className="px-3 py-2">Opened</th>
                  <th className="px-3 py-2">Age</th>
                  <th className="px-3 py-2">Assignee</th>
                  <th className="px-3 py-2">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((r) => <Row key={r.id} row={r} />)}
              </tbody>
            </table>
          )}
        </div>

        {data && data.totalPages > 1 && (
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

function ScopeToggle({
  scope,
  onChange,
}: {
  scope: 'all' | 'mine';
  onChange: (s: 'all' | 'mine') => void;
}) {
  return (
    <div className="flex gap-1 rounded-md border border-slate-200 bg-white p-0.5">
      {(['mine', 'all'] as const).map((s) => (
        <button
          key={s}
          type="button"
          onClick={() => onChange(s)}
          className={
            'rounded px-2 py-0.5 text-xs font-medium ' +
            (scope === s
              ? 'bg-slate-900 text-white'
              : 'text-slate-700 hover:bg-slate-50')
          }
        >
          {s === 'mine' ? 'Mine' : 'All'}
        </button>
      ))}
    </div>
  );
}

function Row({ row }: { row: ExceptionRow }) {
  const ageClass =
    row.ageDays >= 7
      ? 'text-red-700 font-semibold'
      : row.ageDays >= 3
        ? 'text-amber-700'
        : 'text-slate-600';
  return (
    <tr>
      <td className="px-3 py-2">
        <SeverityBadge severity={row.severity} />
      </td>
      <td className="px-3 py-2">
        <Link href={`/careers/erm/escalations/${row.id}`} className="hover:underline">
          <span className="block text-sm font-medium text-slate-900">
            {EXCEPTION_TYPE_LABEL[row.exceptionType] ?? row.exceptionType}
          </span>
          {row.subjectResourceType && (
            <span className="block text-[11px] text-slate-500">
              {row.subjectResourceType}
            </span>
          )}
        </Link>
      </td>
      <td className="px-3 py-2">
        {row.internLifecycleId ? (
          <Link
            href={`/careers/erm/active-interns/${row.internLifecycleId}`}
            className="block text-sm text-slate-900 hover:underline"
          >
            {row.subjectName ?? '(unknown)'}
          </Link>
        ) : (
          <span className="text-sm text-slate-900">
            {row.subjectName ?? '(unknown)'}
          </span>
        )}
        <span className="block text-[11px] text-slate-500">
          {row.subjectEmployeeId ?? ''}
        </span>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.openedAt ? new Date(row.openedAt).toLocaleDateString() : '—'}
      </td>
      <td className={'px-3 py-2 text-xs ' + ageClass}>
        {row.ageDays}d
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.assignedToName ?? (
          <span className="text-slate-400">Unassigned</span>
        )}
      </td>
      <td className="px-3 py-2">
        <StatusPill status={row.status} />
      </td>
    </tr>
  );
}
