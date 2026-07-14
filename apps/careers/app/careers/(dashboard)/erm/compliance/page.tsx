'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import AlertSeverityDot from '@/components/erm/compliance/AlertSeverityDot';
import type {
  PipelinePage,
  PipelineRow,
} from '@/components/erm/compliance/types';

const FILTER_TABS = [
  { key: '', label: 'All' },
  { key: 'WORK_AUTH_EXPIRING', label: 'Work auth ≤30d' },
  { key: 'I9_DUE', label: 'I-9 §2 pending' },
  { key: 'EVERIFY_OPEN', label: 'E-Verify open' },
  { key: 'EVERIFY_TNC', label: 'E-Verify TNC' },
  { key: 'I983', label: 'I-983 required' },
];

export default function CompliancePipelinePage() {
  const [filter, setFilter] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PipelinePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (filter) params.set('filter', filter);
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<PipelinePage>(
        `/api/v1/erm/compliance/pipeline?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [filter, search, page]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Compliance Tracker"
          subtitle="Work authorization, I-9 Section 2, E-Verify, and I-983 deadlines per intern."
        />

        {data && (
          <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Kpi
              label="Work auth ≤30d"
              value={data.kpi.workAuthExpiring30}
              tone="rose"
            />
            <Kpi
              label="I-9 §2 due/overdue"
              value={data.kpi.i9OverdueOrDueSoon}
              tone="amber"
            />
            <Kpi
              label="E-Verify TNC/overdue"
              value={data.kpi.everifyTncOrOverdue}
              tone="rose"
            />
            <Kpi
              label="I-983 required"
              value={data.kpi.i983Required}
              tone="slate"
            />
          </div>
        )}

        <div className="mb-3 flex flex-wrap items-center gap-2">
          {FILTER_TABS.map((c) => (
            <button
              key={c.key || 'all'}
              type="button"
              onClick={() => {
                setFilter(c.key);
                setPage(0);
              }}
              className={
                'rounded-full border px-3 py-1 text-xs font-medium ' +
                (filter === c.key
                  ? 'border-brand-700 bg-brand-700 text-white'
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
              No interns match this filter.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="px-3 py-2">Intern</th>
                  <th className="px-3 py-2">Work auth</th>
                  <th className="px-3 py-2">I-9 §2</th>
                  <th className="px-3 py-2">E-Verify</th>
                  <th className="px-3 py-2">Flags</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((r) => (
                  <Row key={r.userId} row={r} />
                ))}
              </tbody>
            </table>
          )}
        </div>

        {data && data.totalPages > 1 && (
          <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
            <span>
              Page {data.page + 1} of {data.totalPages} ({data.totalElements}{' '}
              total)
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

function Kpi({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: 'rose' | 'amber' | 'slate';
}) {
  const toneClass =
    tone === 'rose'
      ? 'border-red-200 bg-red-50'
      : tone === 'amber'
        ? 'border-amber-200 bg-amber-50'
        : 'border-slate-200 bg-white';
  return (
    <div className={'rounded-lg border p-3 ' + toneClass}>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </div>
      <div className="mt-1 text-2xl font-semibold text-slate-900">{value}</div>
    </div>
  );
}

function Row({ row }: { row: PipelineRow }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <Link
          href={`/careers/erm/compliance/${row.userId}`}
          className="hover:underline"
        >
          <span className="block text-sm font-medium text-slate-900">
            {row.fullName ?? '(unknown)'}
          </span>
          <span className="block text-[11px] text-slate-500">
            {row.applicantId ?? row.email}
          </span>
        </Link>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        <span className="block">{row.workAuthType ?? '—'}</span>
        <span className="block text-[11px] text-slate-500">
          {row.authorizedUntil ?? 'no expiry'}
        </span>
        <AlertSeverityDot
          severity={row.workAuthSeverity}
          label={
            row.daysUntilExpiration != null
              ? row.daysUntilExpiration + 'd'
              : undefined
          }
        />
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        <span className="block">{row.i9Status ?? '—'}</span>
        <span className="block text-[11px] text-slate-500">
          due {row.i9Section2DueBy ?? '—'}
        </span>
        <AlertSeverityDot
          severity={row.i9Severity}
          label={row.i9DaysUntil != null ? row.i9DaysUntil + 'd' : undefined}
        />
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        <span className="block">{row.everifyStatus ?? '—'}</span>
        <span className="block text-[11px] text-slate-500">
          due {row.everifyDueBy ?? '—'}
        </span>
        <AlertSeverityDot
          severity={row.everifySeverity}
          label={
            row.everifyDaysUntil != null
              ? row.everifyDaysUntil + 'd'
              : undefined
          }
        />
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.i983Required && (
          <span className="inline-block rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-800">
            I-983
          </span>
        )}
      </td>
    </tr>
  );
}
