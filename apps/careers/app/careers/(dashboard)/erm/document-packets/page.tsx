'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import type {
  DocumentPacketListPage,
  DocumentPacketRow,
  PacketStatus,
} from '@/components/erm/documents/types';

const STATUS_FILTERS: PacketStatus[] = [
  'ASSIGNED', 'IN_PROGRESS', 'ALL_SUBMITTED', 'COMPLETED', 'CANCELLED',
];

export default function DocumentPacketsPage() {
  const [search, setSearch] = useState('');
  const [statuses, setStatuses] = useState<PacketStatus[]>([
    'ASSIGNED', 'IN_PROGRESS', 'ALL_SUBMITTED',
  ]);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<DocumentPacketListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      statuses.forEach((s) => params.append('status', s));
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<DocumentPacketListPage>(
        `/api/v1/erm/document-packets?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [search, statuses, page]);

  useEffect(() => { void load(); }, [load]);

  function toggleStatus(s: PacketStatus) {
    setStatuses((cur) =>
      cur.includes(s) ? cur.filter((x) => x !== s) : [...cur, s],
    );
    setPage(0);
  }

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Document Packets"
          subtitle="One packet per intern — tracks every document from assignment to acceptance."
        />

        <div className="mb-3 flex flex-wrap items-center gap-2">
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { setPage(0); void load(); } }}
            placeholder="Search name, email, employee ID"
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm"
          />
          {STATUS_FILTERS.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => toggleStatus(s)}
              className={
                'rounded-full border px-2.5 py-0.5 text-[11px] font-medium ' +
                (statuses.includes(s)
                  ? 'border-brand-700 bg-brand-700 text-white'
                  : 'border-slate-200 text-slate-700')
              }
            >
              {s.replace('_', ' ')}
            </button>
          ))}
        </div>

        {err && (
          <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          {loading && !data ? (
            <div className="h-48 animate-pulse" />
          ) : !data || data.items.length === 0 ? (
            <p className="p-10 text-center text-sm text-slate-500">
              No packets match.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="px-3 py-2">Intern</th>
                  <th className="px-3 py-2">Employee ID</th>
                  <th className="px-3 py-2">Status</th>
                  <th className="px-3 py-2">Progress</th>
                  <th className="px-3 py-2">Assigned</th>
                  <th className="px-3 py-2">Completed</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((p) => <Row key={p.packetId} p={p} />)}
              </tbody>
            </table>
          )}
        </div>

        {data && data.totalPages > 1 && (
          <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
            <span>Page {data.page + 1} of {data.totalPages} ({data.totalElements} total)</span>
            <div className="flex gap-1">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage((x) => Math.max(0, x - 1))}
                className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50"
              >
                Prev
              </button>
              <button
                type="button"
                disabled={data.page + 1 >= data.totalPages}
                onClick={() => setPage((x) => x + 1)}
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

function Row({ p }: { p: DocumentPacketRow }) {
  const pct = p.totalTasks > 0
    ? Math.round((p.acceptedTasks * 100) / p.totalTasks) : 0;
  return (
    <tr>
      <td className="px-3 py-2">
        <Link
          href={`/careers/erm/document-packets/${p.packetId}`}
          className="text-sm font-medium text-slate-900 hover:underline"
        >
          {p.internName ?? '(unknown)'}
        </Link>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{p.internEmployeeId ?? '—'}</td>
      <td className="px-3 py-2">
        <StatusPill status={p.status} />
        {/* Phase 1.6 — surface the intern handoff so ERM knows the
            packet is awaiting verification (vs in-progress upload). */}
        {p.internLocked && p.status !== 'COMPLETED' && (
          <span className="mt-1 block rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-800">
            Awaiting verification
          </span>
        )}
      </td>
      <td className="px-3 py-2">
        <div className="flex items-center gap-2">
          <div className="h-1.5 w-24 overflow-hidden rounded-full bg-slate-100">
            <div className="h-full bg-green-500" style={{ width: `${pct}%` }} />
          </div>
          <span className="text-[11px] text-slate-500">
            {p.acceptedTasks}/{p.totalTasks}
          </span>
        </div>
        {p.rejectedTasks > 0 && (
          <span className="mt-0.5 inline-block text-[10px] text-red-600">
            {p.rejectedTasks} rejected
          </span>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {p.assignedAt ? new Date(p.assignedAt).toLocaleDateString() : '—'}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {p.completedAt ? new Date(p.completedAt).toLocaleDateString() : '—'}
      </td>
    </tr>
  );
}

function StatusPill({ status }: { status: PacketStatus }) {
  const styles: Record<PacketStatus, string> = {
    DRAFT: 'bg-slate-100 text-slate-700',
    ASSIGNED: 'bg-slate-100 text-slate-700',
    IN_PROGRESS: 'bg-amber-100 text-amber-800',
    ALL_SUBMITTED: 'bg-amber-100 text-amber-800',
    COMPLETED: 'bg-green-100 text-green-800',
    CANCELLED: 'bg-red-100 text-red-800',
  };
  return (
    <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${styles[status]}`}>
      {status.replace('_', ' ')}
    </span>
  );
}
