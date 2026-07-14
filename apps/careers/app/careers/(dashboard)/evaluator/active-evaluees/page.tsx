'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { AlertTriangle, ChevronRight, Search } from 'lucide-react';
import api from '@/lib/api';
import type {
  ActiveEvalueeRow,
  ActiveEvalueesPage,
} from '@/components/evaluator/types';

const WORK_AUTH_OPTIONS: { value: string; label: string; tone: string }[] = [
  { value: '',                label: 'All',          tone: 'border-slate-200' },
  { value: 'US_CITIZEN',      label: 'US Citizen',   tone: 'border-green-200' },
  { value: 'PERMANENT_RESIDENT', label: 'Permanent Resident', tone: 'border-green-200' },
  { value: 'F1_CPT',          label: 'F-1 CPT',      tone: 'border-amber-200' },
  { value: 'F1_OPT',          label: 'F-1 OPT',      tone: 'border-amber-200' },
  { value: 'F1_STEM_OPT',     label: 'F-1 STEM OPT', tone: 'border-amber-200' },
  { value: 'H1B',             label: 'H-1B',         tone: 'border-slate-300' },
];

export default function ActiveEvalueesPage() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-6xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>}>
      <ActiveEvalueesInner />
    </Suspense>
  );
}

function ActiveEvalueesInner() {
  const router = useRouter();
  const sp = useSearchParams();
  const prefillFilter = sp?.get('filter') ?? '';

  const [search, setSearch] = useState('');
  const [workAuthType, setWorkAuthType] = useState('');
  const [needsAttention, setNeedsAttention] = useState(prefillFilter === 'overdue');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ActiveEvalueesPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      if (workAuthType) params.set('workAuthType', workAuthType);
      if (needsAttention) params.set('needsAttention', 'true');
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<ActiveEvalueesPage>(
        `/api/v1/evaluator/active-evaluees?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load evaluees');
    } finally {
      setLoading(false);
    }
  }, [search, workAuthType, needsAttention, page]);

  useEffect(() => { void load(); }, [load]);

  function clearFilters() {
    setSearch('');
    setWorkAuthType('');
    setNeedsAttention(false);
    setPage(0);
  }

  const rows = data?.items ?? [];

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/evaluator" className="hover:text-slate-700">← Evaluator home</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Active Evaluees</h1>
        <p className="text-xs text-slate-500">
          Interns assigned to you (auto-linked via DEFAULT_EVALUATOR_EMAIL at offer sign).
        </p>
      </div>

      <div className="space-y-2 rounded-lg border border-slate-200 bg-white p-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(e) => { setPage(0); setSearch(e.target.value); }}
              placeholder="Search name or employee ID"
              className="w-72 rounded-md border border-slate-200 pl-8 pr-3 py-1.5 text-sm"
            />
          </div>
          <label className="inline-flex cursor-pointer items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
            <input
              type="checkbox"
              checked={needsAttention}
              onChange={(e) => { setPage(0); setNeedsAttention(e.target.checked); }}
              className="h-3.5 w-3.5"
            />
            Needs attention
          </label>
          <button
            type="button"
            onClick={clearFilters}
            className="text-xs font-medium text-brand-700 hover:underline"
          >
            Clear filters
          </button>
          <span className="ml-auto text-xs text-slate-500">
            {data?.totalElements ?? 0} evaluees
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[10px] uppercase tracking-wide text-slate-400">Work auth:</span>
          {WORK_AUTH_OPTIONS.map((opt) => {
            const on = workAuthType === opt.value;
            return (
              <button
                key={opt.value || 'all'}
                type="button"
                onClick={() => { setPage(0); setWorkAuthType(opt.value); }}
                className={
                  'rounded-full border px-2.5 py-0.5 text-[11px] ' +
                  (on
                    ? 'border-brand-700 bg-brand-700 text-white'
                    : `${opt.tone} bg-white text-slate-700 hover:bg-slate-50`)
                }
              >
                {opt.label}
              </button>
            );
          })}
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : rows.length === 0 ? (
          <EmptyState />
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Technology</th>
                <th className="px-3 py-2">Months</th>
                <th className="px-3 py-2">Work auth</th>
                <th className="px-3 py-2">Last evaluation</th>
                <th className="px-3 py-2">Pending ack</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <Row
                  key={r.lifecycleId}
                  row={r}
                  onClick={() => router.push(`/careers/evaluator/evaluees/${r.lifecycleId}`)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-xs text-slate-600">
          <span>Page {data.page + 1} of {data.totalPages}</span>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={data.page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50"
            >
              Prev
            </button>
            <button
              type="button"
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function Row({ row, onClick }: { row: ActiveEvalueeRow; onClick: () => void }) {
  const stemOpt = row.workAuthType === 'F1_STEM_OPT';
  const needsAck = row.pendingAckCount > 0;
  const lastEvalLate = row.lastEvaluationAt
    && Date.now() - new Date(row.lastEvaluationAt).getTime() > 30 * 86_400_000;
  const noEvalYet = !row.lastEvaluationAt;
  return (
    <tr className="cursor-pointer hover:bg-slate-50" onClick={onClick}>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{row.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">
          {row.employeeId ?? '—'}
          {row.trainerName && <span className="ml-2">Trainer: {row.trainerName}</span>}
        </p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{row.technology ?? '—'}</td>
      <td className="px-3 py-2">
        <span className="inline-flex rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
          {row.monthsInProgram}mo
        </span>
      </td>
      <td className="px-3 py-2">
        {row.workAuthType ? (
          <span
            className={
              'inline-flex rounded-full px-2 py-0.5 text-[10px] font-semibold ' +
              (stemOpt
                ? 'bg-amber-100 text-amber-800'
                : 'bg-slate-100 text-slate-700')
            }
          >
            {row.workAuthType.replaceAll('_', ' ')}
          </span>
        ) : (
          <span className="text-xs text-slate-400">—</span>
        )}
      </td>
      <td className="px-3 py-2 text-xs">
        {row.lastEvaluationAt ? (
          <div>
            <p className={lastEvalLate ? 'text-red-700 font-semibold' : 'text-slate-700'}>
              {new Date(row.lastEvaluationAt).toLocaleDateString()}
            </p>
            <p className="text-[10px] text-slate-500">{row.lastEvaluationStatus}</p>
          </div>
        ) : (
          <span className="inline-flex items-center gap-0.5 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-800">
            <AlertTriangle className="h-3 w-3" />
            Never
          </span>
        )}
      </td>
      <td className="px-3 py-2">
        {needsAck ? (
          <span className="inline-flex rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-semibold text-red-700">
            {row.pendingAckCount}
          </span>
        ) : (
          <span className="text-xs text-slate-400">—</span>
        )}
        {noEvalYet && (
          <span className="ml-1 text-[10px] text-amber-700">first</span>
        )}
      </td>
      <td className="px-3 py-2 text-right">
        <ChevronRight className="h-4 w-4 text-slate-400" />
      </td>
    </tr>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center gap-3 p-12 text-center">
      <p className="text-sm font-medium text-slate-800">No active evaluees yet.</p>
      <p className="max-w-sm text-xs text-slate-500">
        Interns are auto-linked to you at offer sign when{' '}
        <code className="rounded bg-slate-100 px-1 py-0.5 text-[10px]">DEFAULT_EVALUATOR_EMAIL</code>{' '}
        resolves to your account. If you expect to see interns here, confirm the
        env var is set on Railway and matches your email exactly.
      </p>
    </div>
  );
}
