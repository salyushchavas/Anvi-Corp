'use client';

import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import StagePill from '@/components/erm/applications/StagePill';
import BulkDecisionModal from '@/components/erm/applications/BulkDecisionModal';
import type {
  ApplicationListPage,
  ApplicationRow,
  DecisionKind,
} from '@/components/erm/applications/types';

const STAGE_FILTERS: { key: string; label: string }[] = [
  { key: '', label: 'All' },
  { key: 'APPLIED', label: 'Pending review' },
  { key: 'SHORTLISTED', label: 'Shortlisted' },
  { key: 'HOLD', label: 'Hold' },
  { key: 'INFO_REQUESTED', label: 'Info requested' },
  { key: 'REJECTED', label: 'Rejected' },
  { key: 'WITHDRAWN', label: 'Withdrawn' },
];

// useSearchParams is unsafe outside <Suspense> during static prerendering.
// Wrap the inner reader so Next 14 build doesn't bail.
export default function ApplicationInboxPage() {
  return (
    <Suspense fallback={null}>
      <ApplicationInboxPageInner />
    </Suspense>
  );
}

function ApplicationInboxPageInner() {
  const sp = useSearchParams();
  const initialStage = sp?.get('stage') ?? '';
  const [stage, setStage] = useState<string>(initialStage);
  const [scope, setScope] = useState<'mine' | 'all' | 'unassigned'>('mine');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ApplicationListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [modal, setModal] = useState<DecisionKind | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (stage) params.set('stage', stage);
      if (search.trim()) params.set('search', search.trim());
      params.set('scope', scope);
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<ApplicationListPage>(
        `/api/v1/erm/applications?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load applications');
    } finally {
      setLoading(false);
    }
  }, [stage, search, scope, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const allChecked =
    data != null &&
    data.items.length > 0 &&
    data.items.every((r) => selected.has(r.applicationId));

  function toggleAll() {
    if (!data) return;
    const next = new Set(selected);
    if (allChecked) {
      data.items.forEach((r) => next.delete(r.applicationId));
    } else {
      data.items.forEach((r) => next.add(r.applicationId));
    }
    setSelected(next);
  }
  function toggleOne(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  }

  const selectedIds = useMemo(() => Array.from(selected), [selected]);

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Application Inbox"
          subtitle="Review applications and decide: shortlist, hold, request info, or reject."
        />

        <div className="mb-4 flex flex-wrap items-center gap-2">
          {STAGE_FILTERS.map((f) => {
            const active = stage === f.key;
            return (
              <button
                key={f.key || 'all'}
                type="button"
                onClick={() => {
                  setStage(f.key);
                  setPage(0);
                }}
                className={
                  'rounded-full border px-3 py-1 text-xs font-medium transition-colors ' +
                  (active
                    ? 'border-brand-700 bg-brand-700 text-white'
                    : 'border-slate-200 text-slate-700 hover:bg-slate-50')
                }
              >
                {f.label}
              </button>
            );
          })}
          <div className="ml-auto flex items-center gap-2">
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  setPage(0);
                  void load();
                }
              }}
              placeholder="Search name / email / applicant ID"
              className="w-64 rounded-md border border-slate-200 px-3 py-1.5 text-sm"
            />
            <div className="inline-flex overflow-hidden rounded-md border border-slate-200 text-xs">
              {(['mine', 'unassigned', 'all'] as const).map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => {
                    setScope(s);
                    setPage(0);
                  }}
                  className={
                    'px-3 py-1.5 font-medium transition-colors ' +
                    (scope === s
                      ? 'bg-brand-700 text-white'
                      : 'bg-white text-slate-700 hover:bg-slate-50')
                  }
                >
                  {s === 'mine' ? 'Mine' : s === 'unassigned' ? 'Unassigned' : 'All'}
                </button>
              ))}
            </div>
          </div>
        </div>

        {selected.size > 0 && (
          <div className="mb-3 flex items-center justify-between rounded-md border border-slate-200 bg-slate-50 px-3 py-2">
            <p className="text-sm text-slate-700">
              {selected.size} selected
            </p>
            <div className="flex gap-2">
              {(['SHORTLIST', 'HOLD', 'REJECT'] as DecisionKind[]).map((d) => (
                <button
                  key={d}
                  type="button"
                  onClick={() => setModal(d)}
                  className="rounded-md border border-slate-200 bg-white px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100"
                >
                  {d === 'SHORTLIST'
                    ? 'Shortlist'
                    : d === 'HOLD'
                      ? 'Hold'
                      : 'Reject'}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setSelected(new Set())}
                className="rounded-md px-3 py-1 text-xs text-slate-500 hover:text-slate-700"
              >
                Clear
              </button>
            </div>
          </div>
        )}

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
              No applications match the current filters.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="w-10 px-3 py-2">
                    <input
                      type="checkbox"
                      checked={allChecked}
                      onChange={toggleAll}
                    />
                  </th>
                  <th className="px-3 py-2">Applicant</th>
                  <th className="px-3 py-2">Job</th>
                  <th className="px-3 py-2">Stage</th>
                  <th className="px-3 py-2">Age</th>
                  <th className="px-3 py-2">Owner</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((r) => (
                  <Row
                    key={r.applicationId}
                    row={r}
                    selected={selected.has(r.applicationId)}
                    onToggle={() => toggleOne(r.applicationId)}
                  />
                ))}
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

        {modal && (
          <BulkDecisionModal
            open
            selectedIds={selectedIds}
            decision={modal}
            onClose={() => setModal(null)}
            onApplied={(result) => {
              alert(
                `Succeeded: ${result.succeeded.length}\nFailed: ${result.failed.length}` +
                  (result.failed.length > 0
                    ? '\n\n' +
                      result.failed
                        .map((f) => `${f.applicationId}: ${f.reason}`)
                        .join('\n')
                    : ''),
              );
              setSelected(new Set());
              void load();
            }}
          />
        )}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Row({
  row,
  selected,
  onToggle,
}: {
  row: ApplicationRow;
  selected: boolean;
  onToggle: () => void;
}) {
  return (
    <tr className={selected ? 'bg-brand-50/40' : ''}>
      <td className="px-3 py-2">
        <input type="checkbox" checked={selected} onChange={onToggle} />
      </td>
      <td className="px-3 py-2">
        <Link
          href={`/careers/erm/applications/${row.applicationId}`}
          className="flex items-center gap-3 hover:underline"
        >
          <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-100 text-[10px] font-semibold text-brand-800">
            {initials(row.applicantName)}
          </span>
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-slate-900">
              {row.applicantName ?? '(unknown)'}
            </span>
            <span className="block text-[11px] text-slate-500">
              {row.applicantId ?? row.applicantEmail}
            </span>
          </span>
        </Link>
      </td>
      <td className="px-3 py-2">
        <span className="block text-sm text-slate-800">{row.jobTitle}</span>
        <span className="block text-[11px] text-slate-500">{row.jobType}</span>
      </td>
      <td className="px-3 py-2">
        <StagePill stage={row.stage} />
      </td>
      <td className="px-3 py-2">
        <span
          className={
            row.urgentFlag
              ? 'rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-semibold text-red-700'
              : 'text-xs text-slate-600'
          }
        >
          {row.ageDays}d
        </span>
      </td>
      <td className="px-3 py-2 text-xs text-slate-600">
        {row.ermOwnerName ?? (
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-500">
            Unassigned
          </span>
        )}
      </td>
    </tr>
  );
}

function initials(name: string | null): string {
  if (!name) return '?';
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('');
}
