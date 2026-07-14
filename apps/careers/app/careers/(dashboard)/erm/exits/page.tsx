'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import InitiateExitModal from '@/components/erm/exits/InitiateExitModal';
import {
  EXIT_TYPE_TONE,
  type ErmExitListPage,
  type ErmExitRow,
  type ReadyToExitListPage,
  type ReadyToExitRow,
} from '@/components/erm/exits/types';

type Tab = 'ready' | 'active' | 'closed' | 'all';

const TABS: { key: Tab; label: string }[] = [
  { key: 'ready', label: 'Ready to exit' },
  { key: 'active', label: 'Active exits' },
  { key: 'closed', label: 'Closed' },
  { key: 'all', label: 'All' },
];

export default function ExitsPage() {
  const [tab, setTab] = useState<Tab>('ready');
  const [scope, setScope] = useState<'all' | 'mine'>('all');
  const [search, setSearch] = useState('');
  const [initiateFor, setInitiateFor] = useState<{
    lifecycleId: string;
    suggestedType?: 'COMPLETED' | 'RESIGNED' | 'TERMINATED' | 'EXTENDED';
  } | null>(null);

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Exit / Inactivate"
          subtitle="Initiate exits + run the 8-item checklist + manager override when needed."
        />

        <div className="mb-3 flex flex-wrap items-center gap-2">
          <ScopeToggle scope={scope} onChange={setScope} />
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              onClick={() => setTab(t.key)}
              className={
                'rounded-full border px-3 py-1 text-xs font-medium ' +
                (tab === t.key
                  ? 'border-brand-700 bg-brand-700 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {t.label}
            </button>
          ))}
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search intern"
            className="ml-auto w-56 rounded-md border border-slate-200 px-3 py-1.5 text-sm"
          />
        </div>

        {tab === 'ready' ? (
          <ReadyTab
            scope={scope}
            onInitiate={(row) =>
              setInitiateFor({
                lifecycleId: row.internLifecycleId,
                suggestedType: row.suggestedExitType,
              })
            }
          />
        ) : (
          <ExitListTab tab={tab} scope={scope} search={search} />
        )}

        {initiateFor && (
          <InitiateExitModal
            defaultLifecycleId={initiateFor.lifecycleId}
            defaultExitType={initiateFor.suggestedType}
            onClose={() => setInitiateFor(null)}
          />
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

function ReadyTab({
  scope,
  onInitiate,
}: {
  scope: 'all' | 'mine';
  onInitiate: (row: ReadyToExitRow) => void;
}) {
  const [data, setData] = useState<ReadyToExitListPage | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<ReadyToExitListPage>(
        `/api/v1/erm/exits/ready?scope=${scope}&pageSize=50`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load ready-to-exit list');
    }
  }, [scope]);

  useEffect(() => {
    void load();
  }, [load]);

  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (!data) return <div className="h-40 animate-pulse rounded-lg bg-slate-100" />;
  if (data.items.length === 0) {
    return (
      <p className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
        No active interns currently signal ready-to-exit.
      </p>
    );
  }

  return (
    <ul className="space-y-2">
      {data.items.map((r) => (
        <li
          key={r.internLifecycleId}
          className="flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 py-3"
        >
          <div className="min-w-0 flex-1">
            <Link
              href={`/careers/erm/active-interns/${r.internLifecycleId}`}
              className="text-sm font-medium text-slate-900 hover:underline"
            >
              {r.internName ?? '(unknown)'}
            </Link>
            <span className="ml-2 text-[11px] text-slate-500">
              {r.employeeId} · {r.daysActive}d active
            </span>
            <ul className="mt-1 list-disc pl-5 text-xs text-slate-700">
              {r.signals.map((s, i) => (
                <li key={i}>{s}</li>
              ))}
            </ul>
          </div>
          <span
            className={
              'rounded-full px-2 py-0.5 text-[11px] font-semibold ' +
              EXIT_TYPE_TONE[r.suggestedExitType]
            }
          >
            Suggest: {r.suggestedExitType}
          </span>
          <button
            type="button"
            onClick={() => onInitiate(r)}
            className="rounded-md bg-red-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-700"
          >
            Initiate exit
          </button>
        </li>
      ))}
    </ul>
  );
}

function ExitListTab({
  tab,
  scope,
  search,
}: {
  tab: Tab;
  scope: 'all' | 'mine';
  search: string;
}) {
  const [data, setData] = useState<ErmExitListPage | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const stateFilter =
    tab === 'active' ? 'ACTIVE' : tab === 'closed' ? 'CLOSED' : '';

  const load = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      params.set('scope', scope);
      if (stateFilter) params.set('state', stateFilter);
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<ErmExitListPage>(
        `/api/v1/erm/exits?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load exits');
    }
  }, [scope, stateFilter, search, page]);

  useEffect(() => {
    void load();
  }, [load]);

  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (!data) return <div className="h-40 animate-pulse rounded-lg bg-slate-100" />;
  if (data.items.length === 0) {
    return (
      <p className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
        No exits match the current filters.
      </p>
    );
  }

  return (
    <>
      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50">
            <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              <th className="px-3 py-2">Intern</th>
              <th className="px-3 py-2">Type</th>
              <th className="px-3 py-2">Exit date</th>
              <th className="px-3 py-2">Checklist</th>
              <th className="px-3 py-2">Age</th>
              <th className="px-3 py-2">State</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {data.items.map((r) => <Row key={r.exitRecordId} row={r} />)}
          </tbody>
        </table>
      </div>
      {data.totalPages > 1 && (
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
    </>
  );
}

function Row({ row }: { row: ErmExitRow }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <Link
          href={`/careers/erm/exits/${row.exitRecordId}`}
          className="block text-sm font-medium text-slate-900 hover:underline"
        >
          {row.internName ?? '(unknown)'}
        </Link>
        <span className="block text-[11px] text-slate-500">
          {row.employeeId}
        </span>
      </td>
      <td className="px-3 py-2">
        <span
          className={
            'rounded-full px-2 py-0.5 text-[10px] font-semibold ' +
            EXIT_TYPE_TONE[row.exitType]
          }
        >
          {row.exitType}
        </span>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.exitDate ?? '—'}
        {row.lastWorkingDay && row.lastWorkingDay !== row.exitDate && (
          <span className="ml-1 text-[10px] text-slate-500">
            (LWD {row.lastWorkingDay})
          </span>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.completeItems + row.waivedItems + row.notApplicableItems} /{' '}
        {row.totalItems}
        {row.pendingItems > 0 && (
          <span className="ml-1 text-[10px] text-amber-700">
            ({row.pendingItems} pending)
          </span>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.daysSinceInitiate}d
      </td>
      <td className="px-3 py-2">
        <StatePill state={row.overallState} overridden={row.managerOverridden} />
      </td>
    </tr>
  );
}

function StatePill({
  state,
  overridden,
}: {
  state: 'ACTIVE' | 'READY_TO_CLOSE' | 'CLOSED';
  overridden: boolean;
}) {
  const tone =
    state === 'CLOSED'
      ? 'bg-slate-100 text-slate-700'
      : state === 'READY_TO_CLOSE'
        ? 'bg-green-100 text-green-800'
        : 'bg-amber-100 text-amber-800';
  return (
    <span
      className={
        'inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ' + tone
      }
    >
      {state}
      {overridden && state === 'CLOSED' && (
        <span className="ml-1 text-[9px]">(override)</span>
      )}
    </span>
  );
}
